# Dance Regulations Chatbot: RAG with Astra DB, LangChain4j, Spring Boot, React

## Context

The goal is a text chatbot that answers questions about dance competition regulations (initially Svenska Danssportförbundet's BRR regulations, `reglemente-brr-2026-08-01.pdf`, 39 pages). The repo (`C:\Users\stefan\salt\pgp\regulations-agent\repo`) started completely empty — this is a greenfield project, no existing code to reuse.

The source document is deeply hierarchically numbered (B1 → B7, down to e.g. B4.5.1.1), has a table of contents with page numbers, and many dense data tables (points systems, tempo/rule-group per age group, acrobatics rules) where a row's meaning depends on its column headers. It also references external documents ("the general regulations", "Judges' information and practice") that are not part of the data source — the chatbot must be able to say that such things are outside its knowledge. Abbreviations (RG, AR1–3, UK, FS, ITT) are defined once but used throughout, which affects the retrieval strategy.

Decisions that shape the plan (confirmed with the user):
- **LLM & embeddings:** OpenAI for both chat and embeddings.
- **Astra DB:** did not exist yet — the plan includes concrete setup steps.
- **Scope:** built from the start to handle **multiple** regulation documents, not just this one.
- **Citations:** answers must include paragraph/section + page number, so the user can verify against the original.

The user then added four more sources in the same folder (`c:\Users\stefan\salt\pgp\regulations-agent\`), which confirmed and sharpened the multi-document requirement — they differ structurally far more than expected:

| File | Type | Language | Structure |
|---|---|---|---|
| `reglemente-brr-2026-08-01.pdf` | PDF | Swedish | Deep numbering `B1`…`B4.5.1.1`, TOC with page numbers, heavy data tables |
| `TR_0011_10_Lindy_Hop_Rules.pdf` (WRRC) | PDF | English | Decimal numbering `1`, `1.1`, `1.1.1`, TOC, 6 pages |
| `JR_0006_10_Lindy_Hop_Guidelines_for_judges.pdf` (WRRC) | PDF | English | Simple numbering `1`, `2`, `3`, no TOC, 5 pages |
| `Overall rules for Nordic Championship_UpdatedMARCH2026.pdf` | PDF | English | Decimal numbering `1.`…`14.3.1`, TOC, tables per dance discipline |
| `Competitions - International Lindy Hop Championships.htm` | **HTML** (saved web page) | English | **No numbering at all** — heading/division-based (Novice/Intermediate/Advanced/All-Star × Draw/Jazz/Strictly), no page numbers |

This has two consequences for the plan compared to if it had only been the BRR document:
1. **The sources vary greatly in format and numbering style** (PDF *and* HTML, deep letter+dot numbering, plain decimal numbering, no numbering at all). See the next section for how this is handled — in short: by **converting everything to one single uniform format (Markdown) once, before anything reaches the backend**, so that section number/page number can still be optional metadata fields rather than format-specific parsing logic in the app.
2. **Multiple documents define overlapping rules differently** (e.g. Bugg is regulated by both the BRR regulations and the Nordic Championship rules, with different details). The chatbot must always be clear about **which rule set/federation** an answer comes from, and never silently blend rules from different sources — otherwise a competitor could end up following the wrong federation's rule. This is built into the system prompt (see the RAG query flow below).

**Central design principle:** PDF and HTML are just *source formats* — they never exist inside the backend. Before anything is ingested, **every source document is converted once to plain Markdown** (see the "Source documents → Markdown" section below), preserving the heading hierarchy, with tables as real Markdown tables, and page markers (`<!-- page:14 -->`) inserted at every page break in the PDF sources. The conversion is done manually / with the help of an LLM (e.g. Claude, which had already read and structured the content of all five documents earlier in this planning conversation), carefully reviewed against the original (especially the tables, since a wrong number there could give a competitor the wrong rule), and committed as `docs/sources/<document_id>.md` in the repo — that becomes the **canonical, versioned source** the backend actually runs against.

**Backend/ingestion in Java therefore never touches PDF or HTML.** It only ever sees `.md` files, and only needs a **Markdown-aware chunker** (a well-established, well-tested kind of problem, with libraries that already understand heading levels and tables) — not LangChain4j's generic `DocumentSplitter` classes (which only see flat text), and not brittle PDF/HTML structural parsers (PDFBox text-position heuristics, Tabula, Jsoup boilerplate-stripping — all of that is deliberately avoided). New documents added in the future are converted to Markdown the same way, outside the app, before being uploaded — see the section on adding new documents below. LangChain4j is used for what it's good at: embedding/vector store, retrieval, prompt orchestration, chat memory.

## Repo structure

Monorepo, Maven backend + Vite/React/TypeScript frontend:

```
repo/
├── .gitignore, README.md
├── docs/sources/
│   ├── raw/            (original files, unchanged, kept as a revision trail)
│   │   ├── reglemente-brr-2026-08-01.pdf
│   │   ├── TR_0011_10_Lindy_Hop_Rules.pdf
│   │   ├── JR_0006_10_Lindy_Hop_Guidelines_for_judges.pdf
│   │   ├── Overall rules for Nordic Championship_UpdatedMARCH2026.pdf
│   │   └── Competitions - International Lindy Hop Championships.htm
│   ├── reglemente-brr-2026-08-01.md          (converted, canonical — what ingestion actually reads)
│   ├── wrrc-tr-0011-lindy-hop-rules.md
│   ├── wrrc-jr-0006-lindy-hop-judging-guidelines.md
│   ├── nordic-championship-overall-rules.md
│   └── ilhc-competitions.md
├── backend/   (Java 21, Spring Boot 3.5.x, Maven)
│   ├── .env.example
│   ├── src/main/java/se/sveki/regulationsagent/
│   │   ├── config/        AstraDbConfig, OpenAiConfig, LangfuseConfig
│   │   ├── ingestion/      MarkdownChunkAssembler (+ Section/breadcrumb model),
│   │   │                   AbbreviationExtractor, IngestionService,
│   │   │                   RegulationDocument (JPA entity)
│   │   ├── rag/            RegulationAssistant (AiServices), RegulationContentRetriever,
│   │   │                   QueryExpansionService
│   │   ├── tracing/        LangfuseChatModelListener, LangfuseClient
│   │   └── web/            ChatController, AdminIngestionController
│   └── src/main/resources/application*.yml, prompts/system-prompt.txt
└── frontend/  (React + TS + Vite)
    └── src/api/chatClient.ts, components/{ChatWindow,MessageList,MessageBubble,CitationList,ChatInput}
```

Maven was chosen over Gradle (better match with Spring/LangChain4j examples, simpler dependency management for a single-module project). Vite was chosen over CRA (CRA is deprecated).

`git init` was done as part of Phase 0. Original files (PDF/HTML) live in `docs/sources/raw/` as a revision trail; the converted Markdown files in `docs/sources/` are what ingestion actually reads.

## Astra DB: setup and schema

1. Create an account at astra.datastax.com, create a **Serverless (Vector) Database** (name e.g. `regulations-agent`). The region cannot be changed later. **Observed in practice:** on the free/starter tier, EU regions (e.g. AWS `eu-central-1`/`eu-west-1`) are greyed out in the create-database view, and "Unlock All Regions" just reloads the same page instead of unlocking them — this likely requires adding a payment method/pay-as-you-go plan to the account (at the org level), not something solvable from the database form itself. Two options:
   - **Recommended to get moving:** create the database in an available region (e.g. AWS `ap-south-1`/Mumbai, or whichever region isn't locked) for development/Phase 0–2, and migrate to an EU region later (new database + full re-ingestion from the committed `.md` files, which is cheap since they're already versioned in the repo) if EU data-residency requirements become relevant before production.
   - **Alternatively:** add billing information to the DataStax account first (Settings → Billing at the org level) if an EU region is needed from the start, then create the database.
2. Generate an **Application Token** (`AstraCS:...`) and note the **API Endpoint** (`https://<db-id>-<region>.apps.astra.datastax.com`).
3. Create a collection `regulation_chunks` via the Astra Data API with `dimension: 1536` (matches `text-embedding-3-small`) and `metric: "cosine"`. **Important:** dimension is locked per collection — switching to `text-embedding-3-large` (3072 dim) requires a new collection + full re-ingestion.

**Chunk schema** (JSON document in Astra + `$vector`):
`_id`, `document_id`, `document_title`, `federation`, `dance_types[]`, `version`/`effective_date`, `source_type` (`pdf`/`html` — **purely descriptive metadata about the original source format**, filled in by the admin on upload; the backend never parses PDF/HTML itself, it only ever sees the already-converted `.md` file), `source_language` (`sv`/`en`), `section_number` (e.g. `"B4.5.1"` — **optional**, absent for documents without numbering such as the ILHC page), `section_title`, `section_path` (breadcrumb, e.g. `"B4 > B4.5 > B4.5.1"` or for HTML-origin content `"Strictly Contests > Novice Strictly"`), `page_number` (+ `page_number_end`, **optional** — derived from `<!-- page:N -->` markers in the Markdown file, absent for documents without page markers such as the ILHC source), `source_url`/`source_anchor` (for documents with HTML origin, replaces the page number as a verification reference), `chunk_type` (`text`/`table`/`glossary`), `chunk_index`, `text`.

Since `section_number` and `page_number` are now optional (four out of five source documents are missing one or both), both the storage schema and the citation formatting further down must explicitly handle the "missing" case rather than assume the fields always exist.

**LangChain4j integration:** `com.datastax.astra:langchain4j-astradb` (`AstraDbEmbeddingStore`). **Risk flag:** this module is less battle-tested than the OpenAI integration. Do a short spike in Phase 1 (write/read a test vector, verify metadata `Filter` works for per-document scoping). Fallback if problems arise: DataStax's own `astra-db-java` client behind a custom `EmbeddingStore` adapter — isolated behind the `EmbeddingStore` interface so the swap stays small.

## Source documents → Markdown (one-time conversion, done outside/before the backend pipeline)

This replaces what would otherwise have been a bespoke PDF/HTML structural parser in the backend. Done once per source document (now for the five existing ones, and for every new document added later) — **not** as an automated runtime service in the Spring Boot app:

1. **Convert** the source (PDF or saved HTML) to Markdown, with the help of an LLM (Claude — which had already read and correctly structured the content of all five documents in this planning conversation, tables included) or manually. During conversion:
   - The heading hierarchy is mapped to Markdown headings (`#`…`######`) mirroring the document's actual numbering where it exists (e.g. `## B4.5 Poängsystem och rankinglistor...`, `### 2.1.1 PRELIMS`) — for the ILHC page, which has no numbering, headings are just the division/section name (`## Novice Strictly`).
   - Tables are converted to real Markdown tables (`| ... | ... |`), which already keeps the column header attached to every row — the extraction problem that would otherwise have required Tabula/heuristics largely disappears.
   - **Page markers** are inserted at every page break in PDF sources, e.g. `<!-- page:14 -->`, so the page number can be recovered per chunk later. The HTML source (ILHC) has no pages — no markers there, `page_number` becomes `null` for it.
   - Repeated running headers/footers (e.g. `"2026-08-01 Sida X av 39"`, `"Ref.No : TR_0011_1.0 Page X/Y"`) and boilerplate (navigation/scripts in the HTML file) are omitted entirely from the Markdown result — the conversion step cleans that up, no regex heuristics are needed in the backend.
2. **Review** the converted Markdown file against the original, page by page/table by table — especially the heavy tables (BRR's points/tempo tables, the Nordic rules' large table of tempo/heat length per discipline and age group) since a mis-converted number could give a competitor the wrong rule.
3. **Commit** the file as `docs/sources/<document_id>.md` in the repo. The Markdown file is now the canonical, versioned source of truth for that document — a `git diff` between regulation versions becomes readable text instead of binary PDF diffs.

## Ingestion pipeline (Java, backend)

The backend now only needs to understand **one single, simple format**: committed Markdown. No PDFBox, no Tabula, no Jsoup boilerplate-handling in the runtime code.

1. **`MarkdownChunkAssembler`:** parses the `.md` file with a CommonMark library with table support (`com.vladsch.flexmark:flexmark-all`, or `org.commonmark:commonmark` + tables extension) into an AST. Walks the AST and builds the same `Section` tree/breadcrumb model as before (`section_number` is parsed from the heading text if it follows a numeric pattern, otherwise `null`; `section_title` = the remaining heading text; `section_path` = a breadcrumb of the heading stack), but now from a clean, uniform tree structure instead of page-by-page heuristics.
2. **Page numbers:** the HTML-comment markers (`<!-- page:N -->`) inserted during conversion are picked out during the AST walk and set as `page_number` on every subsequent node, until the next marker. If markers are absent (the ILHC source), `page_number = null`.
3. **Table handling:** every Markdown table node becomes an atomic chunk (`chunk_type = table`). The rows' column headers are already present in the table structure; for the best embedding quality a short, self-contained text line is still synthesized per table row (e.g. "Ungdom, RG 2: Tempo 44 takter/min...") rather than just embedding the raw Markdown table syntax.
4. **Chunking:** one chunk per leaf section if it fits within ~500–800 tokens; otherwise paragraph-split with overlap, and the section heading/breadcrumb is repeated in every sub-chunk so it is self-describing. A separate **glossary chunk** (`chunk_type = glossary`) is carved out of abbreviation/term definitions (BRR has RG/AR1-3/UK/FS/ITT; the WRRC/Nordic documents have their own terms such as "Spotlight"/"All Skate"/"Jam" — if a document has no consolidated glossary, this is flagged during manual review so a glossary section can be added to the Markdown file if needed).
5. **Metadata:** `document_id`/`title`/`federation`/`dance_types`/`version`/`effective_date`/`source_type`/`source_language` are supplied by the admin on upload (not guessed from text); `section_number`/`section_title`/`section_path`/`page_number` come from the AST walk.
6. **Abbreviations:** an `AbbreviationExtractor` builds a `Map<abbreviation, expansion>` per document from the glossary chunk, stored in the document registry, used for query expansion (see the RAG pipeline).
7. **Embedding + upsert:** `OpenAiEmbeddingModel` (`text-embedding-3-small`), batched `embedAll`, `AstraDbEmbeddingStore.addAll`.
8. **Admin workflow (two steps — the human review during the conversion step is the first safeguard against incorrect rules in the answers, this is the second):**
   - `POST /api/admin/documents/preview` — takes the already-converted `.md` file + metadata → runs chunking only (no embedding/upsert), returns the chunk list as JSON for review.
   - `POST /api/admin/documents/commit` — embeds and writes to Astra + creates/updates the `RegulationDocument` registry entry.
   - Protected by a simple `ADMIN_API_KEY` header.
9. **Document registry:** JPA entity `RegulationDocument` (H2 locally, Postgres later) — `id`, `title`, `federation`, `danceTypes`, `version`, `effectiveDate`, `sourceFilename` (points at `docs/sources/<id>.md`), `ingestedAt`, `status`, abbreviation glossary. This is what makes the system multi-document-ready from the start (used for a document filter dropdown in the frontend and a metadata `Filter` at retrieval time).
10. **Adding a new document going forward:** convert the source to Markdown following the process above (outside the app), review it, commit it to `docs/sources/`, then upload the `.md` file via the admin workflow. This is a deliberate manual step — automatic PDF/HTML conversion inside the app was deliberately left out in favor of simplicity and reviewability.

## RAG query flow (Java, LangChain4j)

- **API:** `POST /api/chat` — `{ conversationId?, message, documentFilter?: string[] }` → `{ conversationId, answer, citations: [{sectionNumber, sectionTitle, pageNumber, documentTitle, snippet}] }`. The MVP is non-streaming (easier to debug citation correctness against); streaming (`/api/chat/stream` via `StreamingChatLanguageModel` + SSE) is a Phase 4/5 enhancement.
- **Retrieval:** `EmbeddingStoreContentRetriever` (top-k ~6, `minScore` ~0.65, tuned empirically). Metadata filtering via LangChain4j's `Filter`/`MetadataFilterBuilder` built from `documentFilter`. After the primary hit: a separate lookup of `chunk_type=glossary` for the involved documents, always included in the context. `QueryExpansionService` recognizes uppercase abbreviations in the question and appends their expansion (from the abbreviation glossary) to the text that gets embedded for search.
- **Prompt** (`prompts/system-prompt.txt`): answer in whichever language the user's question was asked in (product decision — the chatbot mirrors the user, it does not hardcode a single answer language), regardless of the source document's own language; three of the five sources are in English and one (BRR) is in Swedish, so the model must translate the substance but **keep the section number/heading in its original form** in the citation, e.g. `(TR_0011 §2.1.1 "Prelims", WRRC Lindy Hop Rules)` even when answering in Swedish, or `(B4.5.1 Poängsystem, sid 14)` even when answering in English. Answer only from the given context. The citation format branches depending on what metadata the chunk has: has `section_number` + `page_number` → `(B4.5.1 Poängsystem, sid 14)`; missing a page number (HTML source) → `(section "Novice Strictly", ILHC page)`; missing a section number but has a page → `(heading "Judges appointment", p. 3)`. The model must **never fabricate a numeric citation it wasn't given in context**.
  **Cross-source conflict handling (critical now that several rule sets overlap):** if the question doesn't specify which rule set/federation is meant (e.g. "how many judges are required?" without mentioning BRR, WRRC, or Nordic Championship) and retrieval has pulled relevant chunks from **more than one** `document_id`/`federation`, the answer must clearly separate by rule set (e.g. "According to the BRR regulations (Svenska Danssportförbundet): ... According to the Nordic Championship rules: ...") instead of merging them into one undifferentiated answer — otherwise a competitor risks following the wrong federation's rule. If `documentFilter` is set on the request, the answer is based only on the selected document(s).
  Other: say explicitly when something falls outside the ingested knowledge (e.g. references to "the general regulations" or external WRRC pages that are linked but not ingested), and prefer "I don't know" over fabricating a reference.
- Citations in the response are built **server-side** from the metadata of the chunks actually retrieved (not by regex-parsing the model's free text).
- **Conversation memory:** `@MemoryId` = a client-generated `conversationId` (UUID), `MessageWindowChatMemory` (~10 messages), MVP in-memory (`Map<String, ChatMemory>`); flag a Redis need if multiple backend instances become relevant later.
- Central LangChain4j types: `OpenAiChatModel`, `OpenAiEmbeddingModel`, `AstraDbEmbeddingStore`, `EmbeddingStoreContentRetriever`, `AiServices`, `MessageWindowChatMemory`, `ChatModelListener`.

## Langfuse integration

**Risk flag:** LangChain4j lacks a first-party Langfuse integration (unlike Python/JS LangChain). Recommended approach: register a custom `LangfuseChatModelListener implements ChatModelListener` (`onRequest`/`onResponse`/`onError`) on `OpenAiChatModel`, which sends trace/span/generation events to Langfuse's public ingestion API (`POST {LANGFUSE_HOST}/api/public/ingestion`) via a small `LangfuseClient`. One trace per chat call, one span for the retrieval step (requires a manual wrapper `TracingContentRetriever` around `EmbeddingStoreContentRetriever` since there's no equivalent listener SPI for retrieval), one generation for the LLM call (incl. `TokenUsage`).

Alternative (more speculative): OpenTelemetry against Langfuse's OTLP endpoint — only worth considering if you already have OTel infrastructure. Do a short spike in Phase 3 to verify the exact API shapes (both LangChain4j's `ChatModelListener` signature and Langfuse's ingestion schema, since both evolve quickly).

Config: `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_HOST`.

## Frontend (React)

Simple chat view: `ChatWindow` → `MessageList` → `MessageBubble` (with a `CitationList` for assistant messages, rendered as badges e.g. `[B4.5.1, p. 14]` that expand to a snippet) → `ChatInput`. `chatClient.ts` posts to `/api/chat`. MVP is non-streaming (a plain `fetch` + spinner). `conversationId` is generated with `crypto.randomUUID()`.

## Configuration

Environment variables: `OPENAI_API_KEY`, `ASTRA_DB_APPLICATION_TOKEN`, `ASTRA_DB_API_ENDPOINT`, `ASTRA_DB_KEYSPACE`, `ASTRA_DB_COLLECTION`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_HOST`, `ADMIN_API_KEY`. Spring profiles: `application.yml` (shared, `${VAR}` placeholders), plus a local `.env` (auto-loaded via spring-dotenv). `.env`/secrets are never committed; `.env.example` documents every variable.

## Build order (phases)

1. **Phase 0 — Convert source documents + scaffolding.** Convert the five existing sources to Markdown (see the section above), review them against the originals, commit to `docs/sources/`. In parallel: `git init`, Spring Initializr backend, Vite frontend, `.gitignore`/`.env.example`. *Verify:* spot-check each `.md` file against the original (headings, a couple of known table rows), backend starts and responds on the health endpoint, frontend runs `npm run dev`.
2. **Phase 1 — Backend skeleton + Astra connection + ingestion of the BRR regulations' `.md` file + a simple non-RAG chat.** Astra DB spike (create collection, read/write a test vector, verify `Filter`). Build `MarkdownChunkAssembler`. *Verify:* `/api/admin/documents/preview` returns correct section numbers/pages/table transcriptions for spot checks (e.g. B4.5.1).
3. **Phase 2 — RAG retrieval with citations.** Build the retriever, glossary inclusion, query expansion, system prompt, citations DTO. *Verify:* ask real questions (see below) and check that both the answer and the `sectionNumber`/`pageNumber` match the PDF.
4. **Phase 3 — Langfuse tracing.** *Verify:* a chat request produces a trace in the Langfuse dashboard with a retrieval span and a generation span including token usage.
5. **Phase 4 — React frontend.** *Verify:* manual UI walkthrough, citation badges render correctly, conversation context is preserved across turns.
6. **Phase 5 — Support for multiple documents.** Ingest the four added sources (already converted in Phase 0: WRRC Lindy Hop Rules, WRRC judging guidelines, the Nordic Championship rules, the ILHC page) via the admin workflow, one at a time, reviewing the preview result per document. Verify that `documentFilter` correctly scopes retrieval without cross-contamination between documents, and that the cross-source conflict handling in the system prompt (see the RAG query flow) works when a question touches several rule sets at once.

## Verification — example questions

Against the BRR regulations alone (Phase 1–2, before more documents are ingested):
- "How many judges are required at a GP competition?" — check that the cited section/page is correct.
- "What's the difference between Introduktion till tävling and Ungdomskampen?" — check for two distinct, correct citations.
- "What does AR3 mean?" — check that the glossary mechanism gives the right definition even without an exact text match.
- A question about something that only exists in "the general regulations" — check that the model says it's outside its knowledge instead of making up an answer.
- An unrelated/nonsense question — check for a clean "I don't know" answer.

After Phase 5 (multiple documents, including the four added WRRC/Nordic/ILHC sources), verification expands to:
- "How many judges are required for Lindy Hop competitions?" **without** `documentFilter` — check that the answer clearly separates the BRR, WRRC, and Nordic rules instead of blending them (cross-source conflict handling).
- The same question **with** `documentFilter` set to, say, only the WRRC judging guidelines — check that only that source is used.
- A question against the ILHC page (e.g. "What applies to Novice Strictly?") — check that the citation uses `source_url`/heading instead of a fabricated page number, since the source has no page numbering.
- The same question asked once in Swedish and once in English — check that the chatbot answers each in the language it was asked, and that the citation's section label is kept in its original form regardless of answer language (e.g. `B4.5.1 Poängsystem` never becomes "B4.5.1 Points system" in the citation itself, even when the surrounding answer is in English).

## Critical files

- `docs/sources/*.md` — the converted, canonical source documents; the quality of the manual/LLM-assisted conversion and review of these is now the single most important correctness gate in the whole system (more so than any individual code file), since a mis-converted table row directly becomes a wrong rule in the chatbot's answers.
- `backend/.../ingestion/MarkdownChunkAssembler.java` — builds the section tree/breadcrumb and chunks from the Markdown AST (incl. page-marker parsing and table handling); the most central piece of ingestion, though lower risk now than a bespoke PDF parser would have been.
- `backend/.../rag/RegulationContentRetriever.java` — retrieval logic (metadata filter, glossary inclusion, abbreviation expansion) that determines citation correctness.
- `backend/.../resources/prompts/system-prompt.txt` — the system prompt controlling citation format, cross-source conflict handling between rule sets, and "out of scope" handling.
- `backend/.../config/AstraDbConfig.java` — Astra connection/collection wiring, the first thing to validate in the Phase 1 spike.
- `backend/.../tracing/LangfuseChatModelListener.java` — the Langfuse integration point, flagged as a spike/risk area.
