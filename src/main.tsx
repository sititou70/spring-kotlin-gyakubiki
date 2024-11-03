import { FC, StrictMode, useCallback, useState } from "react";
import { createRoot } from "react-dom/client";
import styles from "./main.module.css";
import { getCommonPath } from "./getCommonPath";
import { AnalysisResults } from "./types";

const useLocalStorageState = (key: string) => {
  const defaultValue = localStorage.getItem(key);
  const [value, _setValue] = useState<string>(defaultValue ?? "");

  const setValue = useCallback(
    (newValue: string) => {
      _setValue(newValue);
      localStorage.setItem(key, newValue);
    },
    [key]
  );

  return [value, setValue] as const;
};

const App: FC = () => {
  const [analysisResultsJson, setAnalysisResultsJson] = useLocalStorageState(
    "analysisResultsJson"
  );
  const [searchQuery, setSearchQuery] = useLocalStorageState("searchQuery");

  return (
    <>
      <h1>逆引きくん</h1>

      <label>
        Project Analysis Results:
        <textarea
          value={analysisResultsJson}
          onChange={(e) => setAnalysisResultsJson(e.target.value)}
        />
      </label>

      <h2>Search</h2>
      <label>
        search sql:
        <input
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </label>

      <h3>Result</h3>
      <SearchResult
        searchQuery={searchQuery}
        analysisResultsJson={analysisResultsJson}
      />
    </>
  );
};

const SearchResult: FC<{
  searchQuery: string;
  analysisResultsJson: string;
}> = ({ searchQuery, analysisResultsJson }) => {
  let analysisResults: AnalysisResults;
  try {
    analysisResults = JSON.parse(analysisResultsJson) as AnalysisResults;
  } catch (e) {
    console.error(e);
    return "error";
  }

  const resultQueries = analysisResults.queries.filter(([query, _]) =>
    atob(query).includes(searchQuery)
  );

  const refs = [...analysisResults.callRelations, ...analysisResults.queries];

  const commonPath = getCommonPath(analysisResults);

  return (
    <ul>
      {resultQueries.map(([query, repository]) => (
        <li key={`${query}:${repository}`}>
          <Tree
            targetRef={query}
            refs={refs}
            commonPath={commonPath}
            sqlHighlight={searchQuery}
          />
        </li>
      ))}
    </ul>
  );
};

const Tree: FC<{
  targetRef: string;
  refs: string[][];
  commonPath: string;
  sqlHighlight?: string;
}> = ({ targetRef, refs, commonPath, sqlHighlight }) => {
  const targetRefs = refs.find(([ref]) => ref === targetRef);
  const childrenRefs =
    targetRefs === undefined ? [] : targetRefs.slice(1, targetRefs.length);

  const targetText = targetRef.replace(/ \[.*\]/, "").replace(commonPath, "");

  return (
    <>
      <span
        className={[
          targetRef.includes("/repository/") && styles.repository,
          targetRef.includes("/service/") && styles.service,
          targetRef.includes("/controller/") && styles.controller,
        ]
          .filter((x) => typeof x === "string")
          .join(" ")}
      />
      {sqlHighlight !== undefined ? (
        <SqlQuery query={targetText} highlight={sqlHighlight} />
      ) : (
        targetText
      )}
      {targetRef.includes(".kt") && (
        <button
          onClick={() => {
            const path = targetRef.match(/\((.+)\)/)?.[1];
            if (path === undefined) return;

            fetch(`http://localhost:63342/api/file/${path}`);
          }}
        >
          open
        </button>
      )}
      <ul>
        {childrenRefs.map((ref) => (
          <li key={ref}>
            <Tree targetRef={ref} refs={refs} commonPath={commonPath} />
          </li>
        ))}
      </ul>
    </>
  );
};

const SqlQuery: FC<{ query: string; highlight: string }> = ({
  query,
  highlight,
}) => (
  <pre
    dangerouslySetInnerHTML={{
      __html: atob(query)
        .replace(/</g, "＜")
        .replace(/>/g, "＞")
        .split(highlight)
        .join(`<em>${highlight}</em>`),
    }}
  />
);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
