import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearch } from "wouter";
import { RelativeTime } from "@/components/ui/Time";
import { imageSize } from "@/lib/images";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminImage } from "./api";
import { AdminPage, Cell, Empty, Id, Pill, PagedView, Row, Section, SmallSelect, Table } from "./ui";
import { ActionButton } from "./permissions";
import { PlainRecord } from "./Records";
import { ListBar } from "./Lists";
import { useListParam, useListView } from "./listView";

type Acting = { image: AdminImage; action: "block" | "unblock" };

/**
 * Renters' own images (ADR 0042): every submission by state, newest first, with what its check found. Block stops its
 * digest for every renter who has it (sessions not started are cancelled, each owner is told); Unblock lifts an admin's
 * own block. Both take a reason and are audited.
 */
export function Images() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? (
    <PlainRecord
      title="Image"
      back="/admin/images/"
      backLabel="← All images"
      page="image"
      id={id}
      read={() => adminData(adminApi.GET("/admin/v1/images/{id}", { params: { path: { id } } }))}
    />
  ) : (
    <ImageList />
  );
}

function ImageList() {
  const client = useQueryClient();
  const [state, setState] = useListParam("state", "");
  const view = useListView();
  const [acting, setActing] = useState<Acting | null>(null);
  const images = useInfiniteQuery({
    queryKey: [ADMIN, "images", state, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/images", {
          params: {
            query: { ...(state ? { state } : {}), ...view, ...(pageParam ? { cursor: pageParam } : {}) },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Images">
      <Section title="Renters' own images">
        <div className="flex">
          <SmallSelect
            label="Show"
            value={state}
            onChange={setState}
            options={[
              ["", "All"],
              ["checking", "Being checked"],
              ["ready", "Ready"],
              ["blocked", "Blocked"],
              ["failed", "Not checked"],
            ]}
          />
        </div>
        <ListBar
          list="images"
          columns={["When", "Renter", "Image", "State", "Size", "Critical", "High", "Secrets", ""]}
          filters={{ state: "state" }}
        />
        <PagedView
          query={images}
          what="the images"
          items={(page) => page.items}
          empty={<Empty title="Nothing here">An image a renter submits is listed here.</Empty>}
        >
          {(rows) => (
            <Table
              caption="Images"
              sorts={{ When: "createdAt", State: "state" }}
              columns={["When", "Renter", "Image", "State", "Size", "Critical", "High", "Secrets", ""]}
            >
              {rows.map((image) => (
                <ImageRow key={image.id} image={image} onAct={setActing} />
              ))}
            </Table>
          )}
        </PagedView>
      </Section>
      <ActionSheet
        open={acting !== null}
        onOpenChange={(next) => !next && setActing(null)}
        record={acting?.image}
        typed={acting?.action === "unblock" ? acting.image.id.slice(0, 8) : undefined}
        title={acting?.action === "unblock" ? "Lift this block?" : "Block this image?"}
        change={
          acting?.action === "unblock" ? (
            <>Its digest can be checked and booked again, if the blocklist allows it.</>
          ) : (
            <>
              Its digest is blocked for every renter who has it: no new session starts, sessions not started
              are cancelled uncharged, and each owner is told.
            </>
          )
        }
        confirm={acting?.action === "unblock" ? "Unblock" : "Block"}
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST(
              acting?.action === "unblock" ? "/admin/v1/images/{id}/unblock" : "/admin/v1/images/{id}/block",
              { params: { path: { id: acting!.image.id } }, body: { reason } },
            ),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}

/** One submission: who, what, where its check stands and what it found. */
function ImageRow({ image, onAct }: { image: AdminImage; onAct: (acting: Acting) => void }) {
  return (
    <Row>
      <Cell>
        <RelativeTime value={image.createdAt} />
      </Cell>
      <Cell>
        <Id value={image.userId} page="user" />
      </Cell>
      <Cell>
        <span className="break-all">{image.reference}</span>
        {image.privateRegistry && <span className="block text-caption text-text-2">private registry</span>}
        {image.reason && <span className="block text-caption text-text-2">{image.reason}</span>}
      </Cell>
      <Cell>
        <Pill tone={image.state === "blocked" || image.state === "failed" ? "danger" : "plain"}>
          {image.state}
        </Pill>
      </Cell>
      <Cell>{image.compressedBytes == null ? "" : imageSize(image.compressedBytes)}</Cell>
      <Cell>{image.critical ?? ""}</Cell>
      <Cell>{image.high ?? ""}</Cell>
      <Cell>{image.secrets > 0 ? image.secrets : ""}</Cell>
      <Cell>
        {image.state === "blocked" && image.reason === "admin" ? (
          <ActionButton
            permission="images.unblock"
            variant="quiet"
            onClick={() => onAct({ image, action: "unblock" })}
          >
            Unblock<span className="sr-only"> {image.id}</span>
          </ActionButton>
        ) : image.digest && image.state !== "blocked" ? (
          <ActionButton
            permission="images.block"
            variant="quiet"
            onClick={() => onAct({ image, action: "block" })}
          >
            Block<span className="sr-only"> {image.id}</span>
          </ActionButton>
        ) : null}
      </Cell>
    </Row>
  );
}
