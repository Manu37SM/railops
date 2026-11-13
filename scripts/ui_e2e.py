"""Browser end-to-end through the Angular UI (Chrome via Playwright) against the real local stack.
Usage: python3 ui_e2e.py <screenshot-dir>"""
import base64, hashlib, hmac, json, re, struct, subprocess, sys, time, urllib.request, uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from playwright.sync_api import sync_playwright, expect

UI = "http://localhost:4200"
SHOTS = sys.argv[1] if len(sys.argv) > 1 else "/tmp"
results = []


def check(name, cond, detail=""):
    results.append(cond)
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"   -> {detail}"))


def sql(q):
    return subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc", q], capture_output=True,
                          text=True, env={"PGPASSWORD": "railops", "PATH": "/usr/bin"}).stdout.strip()


def totp(s):
    key = base64.b32decode(s + "=" * (-len(s) % 8)); step = int(time.time()) // 30
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest(); o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def api(method, path, body=None, token=None):
    req = urllib.request.Request("http://localhost:8082" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req) as r:
        raw = r.read(); return json.loads(raw) if raw else {}


email = f"priya.{uuid.uuid4().hex[:6]}@example.com"
password = "konkan-railway-1998"

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()
    console_errors = []
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    # The stub's checkout host doesn't resolve; capture the redirect instead.
    ctx.route("https://pay.example/**", lambda r: r.fulfill(status=200, content_type="text/html", body="<h1>Stub checkout</h1>"))

    # 1. Anonymous availability search
    page.goto(UI + "/search")
    page.get_by_label("Class").select_option("3A")
    page.get_by_role("button", name="Search").click()
    gn_row = page.get_by_role("row").filter(has_text="GN")
    expect(gn_row).to_contain_text(re.compile(r"AVAILABLE-\d+|RAC-\d+|WL-\d+"))
    check("anonymous user sees availability for 3A/GN", True)
    page.screenshot(path=f"{SHOTS}/1-search.png", full_page=True)

    # 2. Book while logged out -> login with returnUrl -> register instead
    gn_row.get_by_role("button", name="Book").click()
    page.wait_for_url(re.compile(r"/login\?returnUrl="))
    check("booking while logged out redirects to login with a returnUrl", "returnUrl=%2Fbook" in page.url, page.url)
    page.get_by_role("link", name="Create an account").click()
    page.wait_for_url("**/register")  # else "Email" can resolve to the outgoing login page's field
    page.get_by_label("Email").fill(email)
    page.get_by_label("Mobile number").fill("98" + str(uuid.uuid4().int)[:8])
    page.get_by_label("Password").fill("short")
    page.get_by_label("Password").blur()
    check("weak password is flagged before submitting", page.get_by_text("Use at least 10 characters.").is_visible())
    page.get_by_label("Password").fill(password)
    page.get_by_role("button", name="Create account").click()
    page.wait_for_url("**/search")
    check("registration logs the passenger in", page.get_by_role("button", name="Log out").is_visible())

    # 3. Booking form with group rules
    page.goto(UI + "/book?run=1&from=0&to=4&cls=3A&quota=GN")
    page.get_by_label("Full name").fill("Lakshmi Menon")
    page.get_by_label("Age").fill("67")
    page.get_by_role("button", name="Add passenger").click()
    p2 = page.get_by_test_id("passenger-2")
    p2.get_by_label("Full name").fill("Anu Menon")
    p2.get_by_label("Age").fill("3")
    p2.get_by_label("Child under 5 travelling without a separate berth").check()
    p2.get_by_label("Age").fill("7")
    # expect() auto-waits: with zoneless change detection the DOM updates a tick after the input event.
    expect(page.get_by_text("Only children under 5 can travel without a berth.")).to_be_visible()
    expect(p2.get_by_label("Child under 5 travelling without a separate berth")).to_be_visible()
    expect(page.get_by_role("button", name="Book and hold seats")).to_be_disabled()
    check("raising a no-berth child's age past 4 shows the rule, keeps the box reachable, blocks submit", True)
    p2.get_by_label("Age").fill("3")
    page.get_by_label("Mobile").fill("9820012345")
    page.screenshot(path=f"{SHOTS}/2-booking-form.png", full_page=True)
    page.get_by_role("button", name="Book and hold seats").click()
    page.wait_for_url(re.compile(r"/bookings/\d{10}$"))
    pnr = page.get_by_test_id("pnr").inner_text()
    check("booking created and detail page shows the PNR", bool(re.fullmatch(r"\d{10}", pnr)), pnr)
    check("senior got a lower berth; child shown as no-berth",
          "LB" in page.get_by_test_id("pax-1").inner_text() and "Child, no berth" in page.get_by_test_id("pax-2").inner_text(),
          page.get_by_test_id("pax-1").inner_text())
    check("seat-hold countdown is running", bool(re.search(r"Seats held for \d+:\d\d", page.locator(".countdown").inner_text())),
          page.locator(".countdown").inner_text())
    page.screenshot(path=f"{SHOTS}/3-booking-pending.png", full_page=True)

    # 4. Pay -> redirect to the allow-listed checkout host
    page.get_by_role("button", name="Pay now").click()
    page.wait_for_url(re.compile(r"^https://pay\.example/checkout/"))
    check("Pay now redirects to the gateway checkout (allow-listed host)", page.url.startswith("https://pay.example/checkout/"), page.url)

    # 5. Simulated PaymentCaptured (Kafka-driven in Phase 9/11), then reload: session must survive the full reload
    sql(f"""UPDATE reservation.allocation SET state='CONFIRMED', hold_expires_at=NULL WHERE passenger_id IN
            (SELECT p.id FROM reservation.passenger p JOIN reservation.pnr_directory d ON d.booking_id=p.booking_id WHERE d.pnr='{pnr}');
            UPDATE reservation.booking SET status='CONFIRMED', hold_expires_at=NULL WHERE pnr='{pnr}';""")
    page.goto(UI + f"/bookings/{pnr}")
    expect(page.get_by_test_id("pnr")).to_have_text(pnr)
    check("full page reload keeps the session (token restored via HttpOnly cookie, never stored in JS storage)",
          page.get_by_role("button", name="Log out").is_visible())
    storage = page.evaluate("() => JSON.stringify({...localStorage}) + JSON.stringify({...sessionStorage}) + document.cookie")
    check("no token in localStorage/sessionStorage, refresh cookie invisible to JS", "eyJ" not in storage and "rt=" not in storage, storage)

    # 6. Cancellation: child-alone rule, quote, confirm
    page.get_by_label("Cancel passenger 1 Lakshmi Menon").check()
    expect(page.locator("ro-problem-alert [role=alert]")).to_contain_text(re.compile("children travelling without a berth", re.I))
    check("selecting only the adult (child would be left alone) is refused at quote time, no Cancel button",
          page.get_by_role("button", name="Cancel selected").count() == 0)
    page.get_by_label("Cancel passenger 2 Anu Menon").check()
    expect(page.get_by_test_id("quote")).to_contain_text("Refund")
    check("adding the child to the selection clears the error and shows the refund",
          page.locator("ro-problem-alert [role=alert]").count() == 0)
    page.screenshot(path=f"{SHOTS}/4-cancel-quote.png", full_page=True)
    page.get_by_role("button", name="Cancel selected").click()
    page.get_by_role("button", name="Yes, cancel").click()
    expect(page.get_by_role("status")).to_contain_text("Refund of")
    expect(page.locator("header .badge")).to_have_text("Cancelled")
    check("whole-booking cancellation succeeds and shows the refund", True)

    # 7. My bookings
    page.get_by_role("link", name="My bookings").click()
    expect(page.get_by_role("link", name=f"PNR {pnr}")).to_be_visible()
    check("My bookings lists the PNR", True)

    # 8. Staff flow: admin creates a controller (API), controller logs in through the UI with OTP
    blob = bytes.fromhex(sql("SELECT encode(mfa_secret_enc,'hex') FROM identity.app_user WHERE email='root.admin@railops.local'") or "00")
    admin_pw = "Local-Only-Bootstrap-Passw0rd"
    if len(blob) < 20:  # fresh DB: enrol the bootstrap admin first
        et = api("POST", "/api/v1/auth/login", {"email": "root.admin@railops.local", "password": admin_pw})["enrolmentToken"]
        admin_secret = api("POST", "/api/v1/auth/mfa/enrol", token=et)["secretBase32"]
        api("POST", "/api/v1/auth/mfa/confirm", {"code": totp(admin_secret)}, et)
        time.sleep(31 - int(time.time()) % 30)
    else:
        admin_secret = AESGCM(base64.b64decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")).decrypt(
            blob[1:13], blob[13:], b"identity.app_user.mfa_secret").decode()
    admin = api("POST", "/api/v1/auth/login", {"email": "root.admin@railops.local", "password": admin_pw, "otp": totp(admin_secret)})["accessToken"]
    ctl_email = f"controller.{uuid.uuid4().hex[:5]}@railops.local"
    created = api("POST", "/api/v1/admin/staff", {"email": ctl_email}, admin)
    api("POST", f"/api/v1/admin/users/{created['userId']}/grants", {"role": "CONTROLLER", "scopeType": "DIVISION", "scopeValue": "BRC"}, admin)

    page.get_by_role("button", name="Log out").click()
    page.wait_for_url("**/login")
    page.get_by_label("Email").fill(ctl_email)
    page.get_by_label("Password").fill(created["oneTimePassword"])
    page.get_by_role("button", name="Log in").click()
    expect(page.get_by_role("heading", name="Set up two-factor authentication")).to_be_visible()
    secret = page.get_by_test_id("mfa-secret").inner_text().strip()
    page.screenshot(path=f"{SHOTS}/5-mfa-enrolment.png", full_page=True)
    page.get_by_label("6-digit code").fill(totp(secret))
    page.get_by_role("button", name="Confirm").click()
    expect(page.locator("ro-problem-alert [role=alert]")).to_contain_text("Two-factor set up")
    check("first staff login forces MFA enrolment in the UI", True)
    time.sleep(31 - int(time.time()) % 30)  # each TOTP step is single-use
    page.get_by_label("Password").fill(created["oneTimePassword"])
    page.get_by_label("Authenticator code").fill(totp(secret))
    page.get_by_role("button", name="Log in").click()
    page.wait_for_url("**/staff")
    page.get_by_role("button", name="Show occupancy").click()
    expect(page.get_by_test_id("occupancy")).to_contain_text("GN")
    check("controller sees run occupancy and the chart buttons",
          page.get_by_role("button", name="Prepare chart 1").is_visible(), "")
    page.screenshot(path=f"{SHOTS}/6-staff.png", full_page=True)
    page.goto(UI + "/book?run=1&from=0&to=4")
    # After a full load the session restore + guard run asynchronously; wait for the redirect, don't sample.
    try:
        page.wait_for_url("**/staff", timeout=8000)
        redirected = True
    except Exception:
        redirected = False
    check("staff can't open passenger booking screens (route guard redirects to /staff after a cold load)",
          redirected, page.url)

    # 9. Accessibility smoke: every form control has an accessible name
    unlabeled = 0
    for path in ["/login", "/register", "/search"]:
        page.goto(UI + path)
        unlabeled += page.evaluate("""() => [...document.querySelectorAll('input,select')].filter(e =>
            !(e.labels && e.labels.length) && !e.getAttribute('aria-label')).length""")
    check("every input/select on public pages has a label", unlabeled == 0, unlabeled)

    # 10. Mobile layout snapshot
    mobile = browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2, is_mobile=True)
    mp = mobile.new_page()
    mp.goto(UI + "/search")
    mp.get_by_role("button", name="Search").click()
    mp.get_by_role("row").filter(has_text="GN").first.wait_for()
    overflow = mp.evaluate("() => document.documentElement.scrollWidth > window.innerWidth")
    check("no horizontal overflow on a 390px phone screen", not overflow)
    mp.screenshot(path=f"{SHOTS}/7-mobile-search.png", full_page=True)

    real_errors = [e for e in console_errors if "401" not in e and "409" not in e and "422" not in e and "ERR_NAME" not in e]
    check("no unexpected browser console errors", not real_errors, real_errors[:3])
    browser.close()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
