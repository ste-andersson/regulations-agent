# Dance Regulations Chatbot

A text chatbot that answers questions about dance competition regulations (the BRR dances: Bugg,
Boogie Woogie, Lindy Hop, Rock'n'Roll, Dubbelbugg, Formation) using RAG (Retrieval-Augmented
Generation), with citations down to paragraph/section and page, and support for multiple rule
sets/federations at once.

See the full plan in `docs/plan.md` for architecture decisions and background.

## Structure

- `docs/sources/` — converted Markdown versions of the source documents (what actually gets ingested).
  `docs/sources/raw/` holds the original files (PDF/HTML) as a revision trail.
- `backend/` — Java 21 / Spring Boot / LangChain4j / Astra DB. RAG pipeline, ingestion, chat API.
- `frontend/` — React + TypeScript + Vite. Simple chat view with citations.

## Getting started

### Backend

```
cd backend
cp .env.example .env   # fill in values
mvn spring-boot:run
```

`.env` is loaded automatically (via spring-dotenv) when the app is started from `backend/`.

### Frontend

```
cd frontend
npm install
npm run dev
```

## Environment variables

See `backend/.env.example` for the full list (OpenAI, Astra DB, Langfuse, admin key).
