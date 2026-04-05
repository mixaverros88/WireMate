# Build Stage
FROM node:22-alpine3.19 AS build-stage
WORKDIR /app
COPY package*.json ./
# Copy patches BEFORE npm install so patch-package's postinstall hook
# can find and apply them (it only runs as part of `npm install`).
COPY patches ./patches
# Vite 8 uses rolldown, which ships its native binary as a platform-specific
# optional dependency (e.g. @rolldown/binding-linux-x64-musl). npm has a
# long-standing bug where optional deps from a lockfile generated on a
# different platform are not always installed — causing
# "MODULE_NOT_FOUND: .../rolldown/dist/shared/binding-*.mjs" at build time.
# Removing the lockfile inside the image forces npm to re-resolve native
# bindings against the actual container arch (linux/amd64 or linux/arm64).
RUN rm -f package-lock.json && npm install --include=optional
COPY . .
RUN npm run build

# Production Stage
FROM nginx:1.25.5-alpine AS final-stage

COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY --from=build-stage /app/default.conf ./etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]