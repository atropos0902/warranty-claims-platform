FROM node:22.18.0-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.28.0-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV CLAIMS_URL=http://claims:8080 PRICING_URL=http://pricing:8080 ASSISTANT_URL=http://assistant:8080 MIGRATION_URL=http://migration:8080
