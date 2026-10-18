/**
 * The setup app's "Show me how" pages (ADR 0033): turning virtualization on in a laptop's startup menu, by maker. The
 * setup app opens `/help/virtualization/<maker>/` for the maker the laptop's firmware names, and `other` for the rest.
 * Text and numbered steps for now. TODO(pictures): add a photo of each maker's menu once we have the laptops to take
 * them on.
 */
export interface MakerHelp {
  /** The last part of the address. */
  slug: string;
  /** The maker's name as people know it. */
  name: string;
  /** The key to press at start-up, in words. */
  key: string;
  /** Where the setting is, and what it's called, once in the menu. */
  steps: string[];
}

/** Every laptop maker the setup app knows, and the page for everyone else. */
export const MAKERS: MakerHelp[] = [
  {
    slug: "asus",
    name: "ASUS",
    key: "F2 (on some desktops, Delete)",
    steps: [
      "If the menu opens in EZ Mode, press F7 for Advanced Mode.",
      "Open Advanced, then CPU Configuration.",
      "Set Intel Virtualization Technology to Enabled. On an AMD laptop the setting is called SVM Mode.",
    ],
  },
  {
    slug: "msi",
    name: "MSI",
    key: "Delete (on some laptops, F2)",
    steps: [
      "Open the advanced view if the menu starts in a simple one (F7).",
      "Look under OC or Advanced for CPU Features.",
      "Set Intel Virtualization Tech to Enabled. On an AMD laptop the setting is called SVM Mode.",
    ],
  },
  {
    slug: "lenovo",
    name: "Lenovo",
    key: "F2 (or Fn+F2; on some models, the small Novo button, then BIOS Setup)",
    steps: [
      "Open the Configuration tab.",
      "Set Intel Virtual Technology to Enabled. On an AMD laptop the setting is called AMD SVM Technology.",
    ],
  },
  {
    slug: "hp",
    name: "HP",
    key: "Esc, then F10 for BIOS Setup",
    steps: [
      "Open Advanced (on some models, System Configuration).",
      "Find Virtualization Technology (VTx), or AMD-V on an AMD laptop, and turn it on.",
    ],
  },
  {
    slug: "acer",
    name: "Acer",
    key: "F2",
    steps: [
      "Open the Advanced tab (on some models, Main).",
      "Set Intel VTX, or AMD-V on an AMD laptop, to Enabled.",
    ],
  },
  {
    slug: "dell",
    name: "Dell",
    key: "F2",
    steps: [
      "Open Virtualization Support (on newer models, under Advanced Setup).",
      "Turn on Enable Intel Virtualization Technology, or the AMD option of the same name.",
    ],
  },
  {
    slug: "other",
    name: "your laptop",
    key: "usually F2, Delete, F10 or Esc",
    steps: [
      "Look through the menu's tabs for a setting called Virtualization, VT-x, Intel Virtualization Technology, SVM or AMD-V.",
      "Set it to Enabled.",
    ],
  },
];
