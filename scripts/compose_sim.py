"""Runs the stack the way deploy/compose/docker-compose.yml + deploy/docker/service.Dockerfile would, on a host
without a Docker daemon. Used to verify the PRODUCTION configuration path (profile "sim" not "local", secrets as
configtree files, separate owner/app DB roles, password-protected Redis, read-only app directory, non-root uid),
which local development never exercises.

Faithful to the files it reads:
  * per-service environment, secrets (source -> target) and profiles come from docker-compose.yml itself;
  * JAVA_TOOL_OPTIONS comes from service.Dockerfile; the app runs from the extracted Boot layers (same command);
  * runs as uid 10001 with the app directory root-owned (read-only to the process) and HOME unwritable.
Differences (unavoidable without containers), each documented: host ports instead of DNS names, a fresh database
on the host PostgreSQL instead of the postgres container, the host Kafka broker, and the web tier emulated by
scripts/web_edge.py (nginx.conf's headers and proxy rules) instead of nginx.

  python3 -I scripts/compose_sim.py up      # build layers, provision DB/Redis, start services, wait for readiness
  python3 -I scripts/compose_sim.py down
"""
import os, pathlib, re, shutil, subprocess, sys, time, urllib.request
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "deploy/compose/docker-compose.yml"
DOCKERFILE = ROOT / "deploy/docker/service.Dockerfile"
SIM = pathlib.Path("/tmp/railops-sim")
DB = "railops_deploy"
PORTS = {"identity-service": 9082, "reservation-service": 9081, "payment-service": 9083,
         "notification-service": 9084, "api-gateway": 9080}
GATEWAY_MGMT = 9090
REDIS_PORT = 6380
ORDER = ["identity-service", "reservation-service", "payment-service", "notification-service", "api-gateway"]
UID = 10001


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=isinstance(cmd, str), check=True, text=True, capture_output=True, **kw).stdout


def rewrite(value: str) -> str:
    """Compose DNS names -> host ports."""
    kafka = pathlib.Path("/tmp/railops-kafka.bootstrap").read_text().strip()
    value = value.replace("jdbc:postgresql://postgres:5432/railops", f"jdbc:postgresql://127.0.0.1:5432/{DB}")
    value = value.replace("kafka:9092", kafka)
    for svc, port in PORTS.items():
        value = value.replace(f"{svc}:8080", f"127.0.0.1:{port}")
    return value


def java_tool_options() -> str:
    text = DOCKERFILE.read_text()
    m = re.search(r'ENV JAVA_TOOL_OPTIONS="(.*?)"', text, re.S)
    return " ".join(m.group(1).replace("\\\n", " ").split())


def provision():
    secrets = ROOT / "deploy/secrets"
    if not (secrets / "jwt_private_key.pem").exists():
        sh(str(ROOT / "scripts/gen-secrets.sh"))
    # PostgreSQL: a fresh database + exactly the role layout of postgres/10-roles.sh
    sh(f"sudo -u postgres psql -qc 'DROP DATABASE IF EXISTS {DB} WITH (FORCE)'")
    for svc in ["reservation", "identity", "payment", "notification"]:
        sh(f"sudo -u postgres psql -qc 'DROP ROLE IF EXISTS {svc}_owner' -c 'DROP ROLE IF EXISTS {svc}_app'")
    sh(f"sudo -u postgres psql -qc 'CREATE DATABASE {DB}'")
    # Like the bind mount: the files themselves (0644), outside the host's 0700 secrets directory.
    pg_secrets = SIM / "pg-secrets"
    shutil.rmtree(pg_secrets, ignore_errors=True)
    pg_secrets.mkdir(parents=True)
    for f in secrets.glob("*_db_password"):
        shutil.copy(f, pg_secrets / f.name)
    os.chmod(SIM, 0o755)
    sh(f"chmod 755 {pg_secrets} && chmod 644 {pg_secrets}/*")
    sh(f"sudo -u postgres env POSTGRES_USER=postgres POSTGRES_DB={DB} SECRETS_DIR={pg_secrets} "
       f"bash {ROOT}/deploy/compose/postgres/10-roles.sh")
    # Redis with a password (the dev Redis on 6379 has none)
    subprocess.run(["redis-cli", "-p", str(REDIS_PORT), "-a", (secrets / "redis_password").read_text(), "--no-auth-warning",
                    "shutdown", "nosave"], capture_output=True)
    sh(["redis-server", "--port", str(REDIS_PORT), "--save", "", "--appendonly", "no", "--daemonize", "yes",
        "--requirepass", (secrets / "redis_password").read_text()])
    # Non-root runtime user, like the image
    if subprocess.run(["id", "-u", "railopsapp"], capture_output=True).returncode != 0:
        sh(f"groupadd --system --gid {UID} railopsapp && useradd --system --uid {UID} --gid {UID} --no-create-home railopsapp")


def layers(service):
    jar = next((ROOT / service / "target").glob(f"{service}-*.jar"))
    dest = SIM / "app" / service
    shutil.rmtree(dest, ignore_errors=True)
    sh(["java", "-Djarmode=tools", "-jar", str(jar), "extract", "--layers", "--launcher", "--destination", str(dest / "x")])
    for layer in ["dependencies", "spring-boot-loader", "snapshot-dependencies", "application"]:
        shutil.copytree(dest / "x" / layer, dest, dirs_exist_ok=True)
    shutil.rmtree(dest / "x")
    sh(f"chown -R root:root {dest} && chmod -R a+rX,go-w {dest}")
    return dest


def secrets_dir(service, spec, top):
    d = SIM / "secrets" / service
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    for s in spec.get("secrets", []):
        src, target = (s, s) if isinstance(s, str) else (s["source"], s.get("target", s["source"]))
        file = (COMPOSE.parent / top[src]["file"]).resolve()
        shutil.copy(file, d / target)
    sh(f"chown -R {UID}:{UID} {d} && chmod 500 {d} && chmod 400 {d}/*")
    return d


def up():
    compose = yaml.safe_load(COMPOSE.read_text())
    provision()
    jto = java_tool_options()
    (SIM / "logs").mkdir(parents=True, exist_ok=True)
    os.chmod(SIM, 0o755)
    for svc in ORDER:
        spec = compose["services"][svc]
        app = layers(svc)
        sec = secrets_dir(svc, spec, compose["secrets"])
        tmp = SIM / "tmp" / svc
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir(parents=True)
        sh(f"chown {UID}:{UID} {tmp}")
        env = {"PATH": "/usr/bin:/bin", "HOME": "/nonexistent", "TZ": "UTC",
               "JAVA_TOOL_OPTIONS": jto.replace("-Djava.io.tmpdir=/tmp", f"-Djava.io.tmpdir={tmp}"),
               "SERVER_PORT": str(PORTS[svc]),
               "SPRING_CONFIG_IMPORT": f"optional:configtree:{sec}/"}
        for k, v in (spec.get("environment") or {}).items():
            env[k] = rewrite(str(v))
        env.pop("HEALTH_PORT", None)
        if "ALLOWED_ORIGINS" in env:
            env["ALLOWED_ORIGINS"] = "http://127.0.0.1:9088"   # MUST equal the public web origin (else browser POSTs 403)
        if "REDIS_HOST" in env:
            env.update(REDIS_HOST="127.0.0.1", REDIS_PORT=str(REDIS_PORT))
        if svc == "api-gateway":
            env["MANAGEMENT_PORT"] = str(GATEWAY_MGMT)
            env["RAILOPS_EDGE_TRUSTED_PROXIES"] = "127.0.0.1/32"   # the web tier (web_edge.py) runs on loopback here
        mig_name = svc.replace("-service", "-migrate")
        if svc.endswith("-service") and mig_name in compose["services"]:
            migrate(mig_name, compose["services"][mig_name], compose["secrets"], app, jto, tmp)
        log = open(SIM / "logs" / f"{svc}.log", "w")
        subprocess.Popen(["runuser", "-u", "railopsapp", "--", "env", "-i", *[f"{k}={v}" for k, v in env.items()],
                          "java", "org.springframework.boot.loader.launch.JarLauncher"],
                         cwd=app, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        ready(svc, GATEWAY_MGMT if svc == "api-gateway" else PORTS[svc])
    print("compose-sim: all services READY")


def migrate(name, spec, top, app, jto, tmp):
    """The compose one-shot migrator: same image layers, its own entrypoint, ONLY the owner secret."""
    sec = secrets_dir(name, spec, top)
    env = {"PATH": "/usr/bin:/bin", "HOME": "/nonexistent", "TZ": "UTC",
           "JAVA_TOOL_OPTIONS": jto.replace("-Djava.io.tmpdir=/tmp", f"-Djava.io.tmpdir={tmp}")}
    for k, v in (spec.get("environment") or {}).items():
        env[k] = rewrite(str(v)).replace("/run/secrets/", f"{sec}/")
    cmd = [a.replace("/opt/app", str(app)) for a in spec["entrypoint"]]
    r = subprocess.run(["runuser", "-u", "railopsapp", "--", "env", "-i", *[f"{k}={v}" for k, v in env.items()], *cmd],
                       cwd=app, capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip().splitlines()
    print(f"  {name:22s} exit {r.returncode}: {[l for l in out if l.startswith('migrate:')]}")
    if r.returncode != 0:
        sys.exit("\n".join(out[-30:]))


def ready(svc, port, timeout=150):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health/readiness", timeout=2) as r:
                if r.status == 200:
                    print(f"  {svc:22s} ready on :{PORTS[svc]}")
                    return
        except Exception:
            pass
        time.sleep(1)
    print(open(SIM / "logs" / f"{svc}.log").read()[-4000:])
    sys.exit(f"{svc} did not become ready")


def down():
    subprocess.run("pkill -u railopsapp -f JarLauncher", shell=True)
    # Match argv exactly (python3 ... web_edge.py), not a substring: `pkill -f` also kills any shell whose own
    # command line merely mentions the script.
    for proc in pathlib.Path("/proc").glob("[0-9]*"):
        try:
            argv = (proc / "cmdline").read_bytes().split(b"\0")
        except OSError:
            continue
        if argv and argv[0].endswith(b"python3") and any(a.endswith(b"web_edge.py") for a in argv[1:3]):
            os.kill(int(proc.name), 15)
    pw = (ROOT / "deploy/secrets/redis_password").read_text()
    subprocess.run(["redis-cli", "-p", str(REDIS_PORT), "-a", pw, "--no-auth-warning", "shutdown", "nosave"], capture_output=True)


if __name__ == "__main__":
    {"up": up, "down": down}[sys.argv[1]]()
