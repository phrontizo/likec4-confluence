# Pin the base image by DIGEST (not the floating 20-alpine tag) so this mock is byte-reproducible.
# This is a stricter pin than the version-TAG pins used elsewhere (likec4 1.58.0, confluence 10.2.13,
# playwright v1.48.0, postgres 15 — exact versions, but mutable tags): a digest is chosen here because a
# re-pushed Alpine/Node patch under 20-alpine would otherwise silently change the mock. Refresh the digest
# deliberately when bumping Node, rather than letting a patch drift in.
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293
WORKDIR /app
COPY server.mjs /app/server.mjs
COPY repos /repos
# Listen on an unprivileged port and drop to the image's built-in non-root `node` user (uid 1000):
# a network-listening test double should not run as root. Port 8080 (>1024) needs no privilege to bind,
# and the COPY'd files are world-readable, so `node` can serve /repos (the server only reads, never
# writes). Reached only over the compose network, so the port number is an internal detail.
ENV REPOS_DIR=/repos PORT=8080
EXPOSE 8080
USER node
CMD ["node", "server.mjs"]
