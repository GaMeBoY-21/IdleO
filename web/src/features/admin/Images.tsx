import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { imageSize } from "@/lib/images";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminImage } from "./api";
import { AdminPage, Cell, Empty, Id, Pill, QueryView, Row, Section, SmallSelect, Table } from "./ui";

type Acting = { image: AdminImage; action: "block" | "unblock" };

/**
 * Renters' own images (ADR 0042): every submission by state, newest first, with what its check found. Block stops its
 * digest for every renter who has it (sessions not started are cancelled, each owner is told); Unblock lifts an admin's
 * own block. Both take a reason and are audited.
 */
export function Images() {
  const client = useQueryClient();
  const [state, setState] = useState("");
  const [acting, setActing] = useState<Acting | null>(null);
  const images = useQuery({
    queryKey: [ADMIN, "images", state],
    queryFn: () => adminData(adminApi.GET("/admin/v1/images", { params: { query: state ? { state } : {} } })),
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
        <QueryView
          query={images}
          what="the images"
          isEmpty={(page) => page.items.length === 0}
          empty={<Empty title="Nothing here">An image a renter submits is listed here.</Empty>}
        >
          {(page) => (
            <Table
              caption="Images"
              columns={["When", "Renter", "Image", "State", "Size", "Critical", "High", "Secrets", ""]}
            >
              {page.items.map((image) => (
                <ImageRow key={image.id} image={image} onAct={setActing} />
              ))}
            </Table>
          )}
        </QueryView>
      </Section>
      <ActionSheet
        open={acting !== null}
        onOpenChange={(next) => !next && setActing(null)}
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
        <Id value={image.userId} />
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
          <Button variant="quiet" onClick={() => onAct({ image, action: "unblock" })}>
            Unblock<span className="sr-only"> {image.id}</span>
          </Button>
        ) : image.digest && image.state !== "blocked" ? (
          <Button variant="quiet" onClick={() => onAct({ image, action: "block" })}>
            Block<span className="sr-only"> {image.id}</span>
          </Button>
        ) : null}
      </Cell>
    </Row>
  );
}
