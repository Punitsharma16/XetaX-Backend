# XetaX deploy (docker compose)

```bash
# on the server (Ubuntu 24.04, docker + compose plugin installed)
git clone <repo> && cd crm/deploy
cp .env.prod.example .env && nano .env          # fill secrets
mkdir -p frontend website
# copy the built panel (from your laptop):
#   scp -r dist/xetax-crm-ui/browser/* ubuntu@SERVER:/path/crm/deploy/frontend/
#   scp -r website/* ubuntu@SERVER:/path/crm/deploy/website/
nano Caddyfile                                   # real hostnames
docker compose up -d --build                     # first build ~5 min
docker compose logs -f app                       # wait for "Started CrmApplication"
```

Update the app later:
```bash
git pull && docker compose up -d --build app
```

Backups (daily cron):
```bash
docker compose exec -T mysql mysqldump -uroot -p"$DB_PASSWORD" crm | gzip > mysql-$(date +%F).sql.gz
docker compose exec -T mongo mongodump --archive --gzip > mongo-$(date +%F).gz
```
