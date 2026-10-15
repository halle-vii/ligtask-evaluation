# Evaluation design and scope

Figma inspection was restricted to the **Evaluation section 60:31** in file `alHz7X2YxjRwABZxct1zmB`. Inspected high-fidelity code/screenshots for its Dashboard 60:35, Overview 55:22, Specific LLM 60:416, and Specific Prompt 108:189 children. No other Figma sections were opened.

Reference: https://www.figma.com/design/alHz7X2YxjRwABZxct1zmB/LIGTASK-App?node-id=60-31

The initial upload workflow uses the Dashboard's gradient blue LIGTASK wordmark, pale background, rounded dashed drop zone, and blue controls. The additional evaluation screens use the overview sidebar/model table/task tabs, model view with left-hand language/context/task filters and metric cards, and prompt view with instance navigation plus separate metadata/results/query/context panels. The two alternative model layouts in the reference are represented by one consistent filter-panel layout, avoiding duplicate result screens.

Implementation uses server-rendered Thymeleaf fragments, existing base CSS, and scoped evaluation CSS; no frontend framework was added. Layout uses responsive grid/flex rather than fixed Figma coordinates. The project had no compatible component library; semantic links, radio inputs, select, tables, and buttons implement the interactions.

The Figma filter asset is downloaded locally to `src/main/resources/static/assets/evaluation-filter.svg`, retains intrinsic 70×73 geometry, and is rendered at half scale in the results toolbar beside search. Its root dimensions were inspected, file content is nonempty, and its rendered placement was checked in Chrome. No temporary asset URLs are used at runtime. Native text back controls and semantic navigation replace unavailable React Code Connect components. The stray filter layer behind the Specific Prompt metadata card is not exposed as a control.

Intentional research-scope adaptations:

- Truth Label / Truth Value becomes **Context condition**, separated from human ground truth.
- Mock percentages and Safe/Unsafe result labels become **N/A**, **Not imported**, and **Not evaluated**, with reasons. No labels or metrics are synthesized.
- Dataset/version and synthetic provenance are visible. A model entry is a design reference, not a configured provider or executed run.
- Full prompt/context/policy text and supplied category/context attributes are inspectable. Missing fields stay unknown; no Figma sample context is copied into dataset records.
- All/reset filters, search, pagination, filtered CSV export, and detail/back navigation work using imported prepared records. Original/revised translation metadata remains source data.

No schema change was needed for the additional read-only screens. Real annotation and result import contracts, finalized model identifiers, metric calculations, live LLM calls, and statistical analysis remain future work. The draft's future-agent instructions do not expand this scope.

The overview, filtered model view, and prompt details were visually inspected in Chrome at the local application's URL. Additional automated checks cover filters/search/pagination, dataset boundaries, preserved back navigation, empty/invalid states, and CSV/HTML escaping.

## Navigation refinement

The evaluation pages now share a persistent, collapsible sidebar. Overview, Prompts, Dataset coverage, model results, and prompt details use the same outer shell. A contextual model-results item replaces the former detail-page back button when a model is selected. Prompt previous/next arrows remain in the content heading. Expanded/collapsed preference is stored locally in the browser, restored before painting, and usable through keyboard-accessible controls with labeled icons.

Dataset coverage is a first-class evaluation route at `/evaluation/{id}/coverage`; saved dataset links and successful imports go there. Legacy dataset detail URLs redirect to it. Sidebar links preserve filters while coverage explicitly reports the whole dataset. Dataset switching preserves the current section; switching from a detail page returns to the new dataset's prompt/model list rather than retaining an invalid prompt ID.
