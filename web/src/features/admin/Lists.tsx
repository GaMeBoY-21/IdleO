import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { useLocation } from "wouter";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { useAddress, useListName } from "./listView";
import { useCan } from "./permissions";
import { Problem } from "./ui";

/** What a list's export gives: the filters in the address, under the names the server's list knows them by. */
function filtersFromAddress(
  params: URLSearchParams,
  known: Record<string, string>,
  tagName: string,
): Record<string, string> {
  const filters: Record<string, string> = {};
  Object.entries(known).forEach(([param, filter]) => {
    const value = params.get(param);
    if (value) {
      filters[filter] = value;
    }
  });
  const tag = params.get(tagName);
  if (tag) {
    filters.tag = tag;
  }
  return filters;
}

/**
 * Above every list: which tag, a saved view, and a CSV of what it shows. The export goes through the same
 * action sheet as any action, with its reason, step-up and approval; the server decides what comes of it.
 */
export function ListBar({
  list,
  filters = {},
  extra = {},
  tags = true,
}: {
  /** The server's name for this list. */
  list: string;
  /** From a filter's name in the address to its name on the server, for the export. */
  filters?: Record<string, string>;
  /** Filters the screen works out itself, by their names on the server: a date range, say. */
  extra?: Record<string, string>;
  tags?: boolean;
}) {
  const can = useCan();
  const client = useQueryClient();
  const id = useId();
  const [params, write] = useAddress();
  const name = useListName();
  const [path, navigate] = useLocation();
  const [exporting, setExporting] = useState(false);
  const [saving, setSaving] = useState("");
  const [shared, setShared] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const views = useQuery({
    queryKey: [ADMIN, "views", list],
    queryFn: () => adminData(adminApi.GET("/admin/v1/views", { params: { query: { list } } })),
    staleTime: 60_000,
  });
  const vocabulary = useQuery({
    queryKey: [ADMIN, "tags"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tags")),
    enabled: tags && can("notes.read"),
    staleTime: 60_000,
  });
  const save = async () => {
    setProblem(null);
    try {
      await adminData(
        adminApi.POST("/admin/v1/views", {
          body: { list, name: saving.trim(), shared, query: params.toString() },
        }),
      );
      setSaving("");
      await client.invalidateQueries({ queryKey: [ADMIN, "views", list] });
    } catch (failed) {
      setProblem(failed);
    }
  };
  const remove = async (view: string) => {
    setProblem(null);
    try {
      const { error, response } = await adminApi.DELETE("/admin/v1/views/{id}", {
        params: { path: { id: view } },
      });
      if (!response.ok) {
        throw ApiError.of(response.status, error);
      }
      await client.invalidateQueries({ queryKey: [ADMIN, "views", list] });
    } catch (failed) {
      setProblem(failed);
    }
  };
  const mine = views.data?.views ?? [];
  return (
    <div className="flex flex-wrap items-end gap-2 text-small">
      {tags && can("notes.read") && (vocabulary.data?.tags.length ?? 0) > 0 && (
        <label className="flex flex-col gap-1 text-caption text-text-2">
          Tag
          <select
            value={params.get(name("tag")) ?? ""}
            onChange={(event) =>
              write((next) => {
                if (event.target.value === "") {
                  next.delete(name("tag"));
                } else {
                  next.set(name("tag"), event.target.value);
                }
              })
            }
            className="min-h-touch rounded-control bg-raised px-2 text-small text-text md:min-h-9"
          >
            <option value="">Any</option>
            {vocabulary.data?.tags.map((tag) => (
              <option key={tag.id} value={tag.id}>
                {tag.label}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className="flex flex-col gap-1 text-caption text-text-2">
        Saved view
        <select
          value=""
          onChange={(event) => {
            const view = mine.find((candidate) => candidate.id === event.target.value);
            if (view) {
              navigate(view.query ? `${path}?${view.query}` : path);
            }
          }}
          className="min-h-touch rounded-control bg-raised px-2 text-small text-text md:min-h-9"
        >
          <option value="">Choose…</option>
          {mine.map((view) => (
            <option key={view.id} value={view.id}>
              {view.name}
              {view.shared ? " (shared)" : ""}
            </option>
          ))}
        </select>
      </label>
      {mine
        .filter((view) => view.mine)
        .slice(0, 1)
        .map((view) => (
          <Button key={view.id} variant="quiet" onClick={() => void remove(view.id)}>
            Delete “{view.name}”
          </Button>
        ))}
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (saving.trim() !== "") {
            void save();
          }
        }}
      >
        <label htmlFor={id} className="flex flex-col gap-1 text-caption text-text-2">
          Save this view as
          <input
            id={id}
            value={saving}
            maxLength={80}
            onChange={(event) => setSaving(event.target.value)}
            className="min-h-touch rounded-control bg-raised px-2 text-small text-text md:min-h-9"
          />
        </label>
        <label className="flex min-h-touch items-center gap-2 md:min-h-9">
          <input type="checkbox" checked={shared} onChange={(event) => setShared(event.target.checked)} />
          Share it
        </label>
        <Button type="submit" variant="quiet">
          Save
        </Button>
      </form>
      {can("export.create") && (
        <Button variant="ghost" onClick={() => setExporting(true)}>
          Export CSV
        </Button>
      )}
      <Problem error={problem} />
      <ActionSheet
        open={exporting}
        onOpenChange={setExporting}
        title="Export this list"
        change="A CSV of what this list shows, with personal data masked, at most 50,000 rows. If it holds personal data, another admin approves it first."
        confirm="Export"
        onConfirm={async (reason) => {
          await fetchExport(
            list,
            { ...filtersFromAddress(params, filters, name("tag")), ...extra },
            params.get(name("sort")),
            reason,
          );
        }}
      />
    </div>
  );
}

/** Asks for the file: a list without personal data downloads now; the sheet says when one with it goes to another admin. */
async function fetchExport(
  list: string,
  filters: Record<string, string>,
  sort: string | null,
  reason: string,
): Promise<void> {
  const {
    data: body,
    error,
    response,
  } = await adminApi.POST("/admin/v1/exports", {
    body: { list, filters, reason, ...(sort ? { sort } : {}) },
    parseAs: "stream",
  });
  if (response.status === 202) {
    return;
  }
  if (!response.ok || !body) {
    throw ApiError.of(response.status, error);
  }
  const url = URL.createObjectURL(await new Response(body).blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `${list}.csv`;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
