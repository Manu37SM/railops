# syntax=docker/dockerfile:1.7
# Angular SPA on unprivileged nginx (uid 101, port 8080). Same-origin: /api/* is proxied to the gateway, so the
# refresh-token cookie stays SameSite=Strict and no CORS is needed.
ARG NODE_IMAGE=node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8

# The image is pinned by tag AND sha256 digest in the ARG above; hadolint cannot see through ${...}.
# hadolint ignore=DL3006
FROM ${NODE_IMAGE} AS build
WORKDIR /web
COPY railops-web/package.json railops-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY railops-web/ ./
RUN npx ng build --configuration production

# The image is pinned by tag AND sha256 digest in the ARG above; hadolint cannot see through ${...}.
# hadolint ignore=DL3006
FROM ${NGINX_IMAGE}
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist/railops-web/browser/ /usr/share/nginx/html/
EXPOSE 8080
