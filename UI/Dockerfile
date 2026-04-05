# Build Stage
FROM node:22-alpine3.19 AS build-stage
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Production Stage
FROM nginx:1.25.5-alpine AS final-stage

COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY --from=build-stage /app/default.conf ./etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]