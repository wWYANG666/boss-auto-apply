.PHONY: dev verify up down logs

dev:
	npm run dev

verify:
	npm run verify
	cd services/ai-worker && pytest -q && ruff check app tests && mypy app
	cd apps/career-runner && npm run typecheck && npm test && npm run build
	cd services/core-api && ./mvnw test

up:
	docker compose up --build

down:
	docker compose down

logs:
	docker compose logs -f --tail=100
