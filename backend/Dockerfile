FROM node:22.23.1-alpine3.24 AS build
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@11.9.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY tsconfig.json ./
COPY src ./src
RUN pnpm build && pnpm prune --prod

FROM node:22.23.1-alpine3.24 AS runtime
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S glib && adduser -S -G glib glib
COPY --from=build --chown=glib:glib /app/package.json ./package.json
COPY --from=build --chown=glib:glib /app/node_modules ./node_modules
COPY --from=build --chown=glib:glib /app/dist ./dist
COPY --chown=glib:glib migrations ./migrations
USER glib
EXPOSE 8080
CMD ["node", "dist/index.js"]
