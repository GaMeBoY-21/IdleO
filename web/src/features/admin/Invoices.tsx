import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearch } from "wouter";
import { ApiError } from "@/api/errors";
import { Money } from "@/components/ui/Money";
import { parseRupees } from "@/lib/money";
import { formatDay } from "@/lib/time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminInvoice } from "./api";
import { AdminPage, Cell, Empty, Filters, Id, PagedView, Row, Section, SmallField, Table } from "./ui";
import { ActionButton } from "./permissions";
import { PlainRecord } from "./Records";
import { ListBar } from "./Lists";
import { useListParams, useListView } from "./listView";

/** Saves one document's PDF, fetched with the admin session when asked. */
export async function downloadAdminInvoice(id: string, number: string): Promise<void> {
  const { data, error, response } = await adminApi.GET("/admin/v1/invoices/{id}/pdf", {
    params: { path: { id } },
    parseAs: "blob",
  });
  if (!response.ok || !(data instanceof Blob)) {
    throw ApiError.of(response.status, error);
  }
  const url = URL.createObjectURL(data);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `${number}.pdf`;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * Invoices (ADR 0039): every tax invoice, receipt, usage invoice and credit note, found by its number or the renter's
 * id; each downloads as the renter's does, and a credit note can be issued against one, for at most what's left of
 * it, with a reason that's audited.
 */
export function Invoices() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? (
    <PlainRecord
      title="Invoice"
      back="/admin/invoices/"
      backLabel="Invoices"
      page="invoice"
      id={id}
      read={() => adminData(adminApi.GET("/admin/v1/invoices/{id}", { params: { path: { id } } }))}
    />
  ) : (
    <InvoiceSearch />
  );
}

function InvoiceSearch() {
  const client = useQueryClient();
  const [asked, setAsked] = useListParams({ number: "", userId: "" });
  const [form, setForm] = useState(asked);
  const view = useListView();
  const [noting, setNoting] = useState<AdminInvoice | null>(null);
  const [rupees, setRupees] = useState("");
  const found = useInfiniteQuery({
    queryKey: [ADMIN, "invoices", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/invoices", {
          params: {
            query: {
              ...(asked.number ? { number: asked.number } : {}),
              ...(asked.userId ? { userId: asked.userId } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: asked.number !== "" || asked.userId !== "" || view.tag !== undefined,
  });
  return (
    <AdminPage title="Invoices">
      <ListBar list="invoices" filters={{ number: "number", userId: "user" }} />
      <Filters onSubmit={() => setAsked({ number: form.number.trim(), userId: form.userId.trim() })}>
        <SmallField
          label="Number"
          value={form.number}
          onChange={(number) => setForm({ ...form, number })}
          width="w-56"
        />
        <SmallField
          label="Or the renter's id"
          value={form.userId}
          onChange={(userId) => setForm({ ...form, userId })}
          width="w-80"
        />
      </Filters>
      {asked.number === "" && asked.userId === "" && view.tag === undefined ? (
        <Empty title="Look a document up">Give its number, or the renter&apos;s id for all of theirs.</Empty>
      ) : (
        <Section title="Documents">
          <PagedView
            query={found}
            what="the invoices"
            items={(page) => page.invoices}
            empty={<Empty title="Nothing found">No document matches that.</Empty>}
          >
            {(rows) => (
              <Table
                caption="Invoices, receipts and credit notes"
                list="invoices"
                sorts={{ Number: "number", Issued: "issuedAt", Total: "total" }}
                columns={["Number", "Kind", "Renter", "Issued", "Taxable", "GST", "Total", ""]}
              >
                {rows.map((item) => (
                  <Row key={item.invoice.id}>
                    <Cell className="tabular">{item.invoice.number}</Cell>
                    <Cell>{item.invoice.title}</Cell>
                    <Cell>
                      <Id value={item.userId} page="user" />
                    </Cell>
                    <Cell className="tabular">{formatDay(item.invoice.issuedAt)}</Cell>
                    <Cell>
                      <Money paise={item.invoice.taxablePaise} />
                    </Cell>
                    <Cell>
                      <Money paise={item.invoice.taxPaise} />
                    </Cell>
                    <Cell>
                      <Money paise={item.invoice.totalPaise} />
                    </Cell>
                    <Cell>
                      <div className="flex gap-1">
                        <ActionButton
                          permission="invoices.pdf.read"
                          variant="quiet"
                          onClick={() => void downloadAdminInvoice(item.invoice.id, item.invoice.number)}
                        >
                          Download<span className="sr-only"> {item.invoice.number}</span>
                        </ActionButton>
                        {item.invoice.kind !== "credit_note" && (
                          <ActionButton
                            permission="invoices.credit-note"
                            variant="quiet"
                            onClick={() => setNoting(item)}
                          >
                            Credit note<span className="sr-only"> against {item.invoice.number}</span>
                          </ActionButton>
                        )}
                      </div>
                    </Cell>
                  </Row>
                ))}
              </Table>
            )}
          </PagedView>
        </Section>
      )}
      <ActionSheet
        open={noting !== null}
        record={noting ?? undefined}
        onOpenChange={(next) => {
          if (!next) {
            setNoting(null);
            setRupees("");
          }
        }}
        title={`Issue a credit note against ${noting?.invoice.number ?? ""}?`}
        change="A credit note takes part of the document back, with its GST in the same proportions; it moves no money by itself. It gets the next number, and the renter sees it with their documents."
        confirm="Issue the credit note"
        extra={
          <SmallField
            label="Amount in ₹, GST included"
            value={rupees}
            onChange={setRupees}
            inputMode="decimal"
          />
        }
        onConfirm={async (reason) => {
          const paise = parseRupees(rupees);
          if (paise === null || paise <= 0) {
            throw new SheetProblem("Give an amount in rupees.");
          }
          await adminData(
            adminApi.POST("/admin/v1/invoices/{id}/credit-notes", {
              params: { path: { id: noting!.invoice.id } },
              body: { amountPaise: paise, reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}
