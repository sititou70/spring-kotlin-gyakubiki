import { AnalysisResults } from "./types";

export const getCommonPath = (analysisResults: AnalysisResults): string => {
  const allPaths: string[][] = [
    ...analysisResults.callRelations.flat(),
    ...analysisResults.queries.map(([_, ref]) => ref),
  ]
    .map((ref) => ref.match(/\((.+)\)/)?.[1])
    .filter(
      (path): path is Exclude<typeof path, undefined> => path !== undefined
    )
    .map((path) => path.split("/"));

  const isEqPath = (path1: string[], path2: string[]): boolean => {
    if (path1.length !== path2.length) return false;

    for (const [index, value1] of path1.entries()) {
      if (value1 !== path2[index]) return false;
    }

    return true;
  };

  const pivotPath = allPaths[0];
  const commonPathLength = [...Array(pivotPath.length).keys()]
    .reverse()
    .find((pathLength) =>
      allPaths.every(
        (path) =>
          pathLength <= path.length &&
          isEqPath(pivotPath.slice(0, pathLength), path.slice(0, pathLength))
      )
    );

  return pivotPath.slice(0, commonPathLength).join("/");
};
