# syntax=docker/dockerfile:1.7
# Angular SPA on unprivileged nginx (uid 101, port 8080). Same-origin: /api/* is proxied to the gateway, so the
# refresh-token cookie stays SameSite=Strict and no CORS is needed.
ARG NODE_IMAGE=node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.30-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03

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
# Upstream rebuilds lag Alpine's security fixes (curl, expat, brotli, libxslt...), and CI's Trivy scan fails on
# fixable HIGH/CRITICAL. Pull the fixes at build time, then drop back to the image's unprivileged user.
USER root
# hadolint ignore=DL3017,DL3018
RUN apk upgrade --no-cache
USER 101
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist/railops-web/browser/ /usr/share/nginx/html/
EXPOSE 8080
