import { cn } from "@/lib/cn";
import { brand } from "./brand";

/** The name's letters, drawn as outlines in the box their text had (scripts/wordmark-path.py): a logo, not a font that is loaded. */
const OUTLINE =
  "M60 -590V-725H284V-590ZM60 0V-528H284V0ZM643 12Q564 12 500 -18Q436 -47 398 -108Q360 -169 360 -264Q360 -359 398 -420Q436 -481 500 -510Q564 -540 643 -540Q672 -540 706 -534Q740 -527 772 -512Q804 -496 826 -470H835V-725H1059V0H877L860 -82H851Q810 -29 756 -8Q701 12 643 12ZM712 -144Q756 -144 784 -160Q811 -176 824 -203Q836 -230 836 -261V-267Q836 -291 829 -312Q822 -333 807 -349Q792 -365 768 -374Q745 -384 712 -384Q668 -384 640 -368Q613 -353 600 -328Q587 -303 587 -273V-255Q587 -225 600 -200Q613 -174 640 -159Q668 -144 712 -144ZM1159 0V-725H1383V0ZM1838 12Q1725 12 1640 -17Q1554 -46 1506 -108Q1459 -169 1459 -264Q1459 -357 1506 -418Q1553 -480 1636 -510Q1719 -540 1828 -540Q1942 -540 2025 -511Q2108 -482 2153 -421Q2198 -360 2198 -264V-226H1693Q1695 -189 1712 -164Q1729 -139 1762 -127Q1794 -115 1840 -115Q1870 -115 1894 -121Q1917 -127 1933 -138Q1949 -148 1957 -161Q1965 -174 1965 -188H2197Q2197 -138 2168 -100Q2140 -63 2090 -38Q2041 -13 1976 0Q1911 12 1838 12ZM1694 -328H1958Q1958 -345 1950 -360Q1942 -375 1926 -387Q1911 -399 1888 -406Q1866 -413 1838 -413Q1794 -413 1764 -402Q1734 -390 1716 -370Q1699 -351 1694 -328ZM2619 12Q2510 12 2427 -18Q2344 -49 2297 -110Q2250 -171 2250 -264Q2250 -357 2297 -418Q2344 -480 2427 -510Q2510 -540 2619 -540Q2728 -540 2811 -510Q2894 -480 2941 -418Q2988 -357 2988 -264Q2988 -171 2941 -110Q2894 -49 2811 -18Q2728 12 2619 12ZM2619 -129Q2668 -129 2698 -146Q2729 -163 2743 -192Q2757 -220 2757 -254V-274Q2757 -308 2743 -336Q2729 -365 2698 -382Q2668 -399 2619 -399Q2570 -399 2540 -382Q2509 -365 2495 -336Q2481 -308 2481 -274V-254Q2481 -220 2495 -192Q2509 -163 2540 -146Q2570 -129 2619 -129Z";

/**
 * The wordmark (web/DESIGN.md "Brand"): the name in lowercase, drawn as outlines. It takes the text colour around it (the
 * brand's volt, `text-live`, in a bar). Its letters are drawn rather than set, so it's on every first screen with no
 * font file to wait for and never shows in another face.
 */
export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-block", className)}>
      <span className="sr-only">{brand.name.toLowerCase()}</span>
      <svg aria-hidden="true" viewBox="0 -934 3004 1200" width="3.004em" height="1.2em" fill="currentColor">
        <path d={OUTLINE} />
      </svg>
    </span>
  );
}

/**
 * The brand mark (public/icon.svg's ring), for where the wordmark is too wide: a collapsed sidebar. It takes the text
 * colour around it.
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 1570 1570" className={className} fill="none">
      <path
        stroke="currentColor"
        strokeWidth="230"
        strokeLinecap="round"
        d="M991 362A470 470 0 1 1 579 362"
      />
    </svg>
  );
}
