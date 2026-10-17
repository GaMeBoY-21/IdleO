import { Fragment, type ReactNode } from "react";

/**
 * A file's name that may wrap after each "/" (a long path in a narrow tile), and nowhere else that the browser
 * wouldn't: a `<wbr>` after every slash. The words a screen reader reads are the name's, unchanged.
 */
export function breakable(name: string): ReactNode {
  const parts = name.split("/");
  return parts.map((part, index) => (
    <Fragment key={index}>
      {part}
      {index < parts.length - 1 && (
        <>
          /<wbr />
        </>
      )}
    </Fragment>
  ));
}
