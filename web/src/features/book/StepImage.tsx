import { Package } from "lucide-react";
import { useEffect, useState } from "react";
import { useFormContext, useWatch } from "react-hook-form";
import { Link } from "wouter";
import { checking, useImage, useImages, useSubmitImage } from "@/api/images";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Option } from "@/components/ui/Option";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState, errorSentence } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { ImageFindings } from "@/features/images/ImageFindings";
import { IMAGE_STATES, imageSize } from "@/lib/images";
import type { BookValues } from "./form";
import { StepTitle } from "./StepTitle";

const ID = "own-image";

/**
 * "1. Your image", for the template that runs the renter's own image (ADR 0042): one of their checked images, or a new
 * reference to check, with a private registry's user name and read-only token. The check's progress, what it found and
 * its warnings show here; only a ready image can be chosen, and the choice is the booking's `imageId`. Without a verified
 * PAN it says why and where to give one. The token is sent once, in the check's own request, and kept nowhere here.
 */
export function StepImage({ number }: { number: number }) {
  const form = useFormContext<BookValues>();
  const chosen = useWatch({ control: form.control, name: "imageId" });
  const images = useImages();
  const submit = useSubmitImage();
  const [reference, setReference] = useState("");
  const [isPrivate, setPrivate] = useState(false);
  const [username, setUsername] = useState("");
  const [token, setToken] = useState("");
  const [checkingId, setCheckingId] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const under = useImage(checkingId);
  const error = form.formState.errors.imageId?.message;

  const choose = (id: string) => form.setValue("imageId", id, { shouldValidate: true, shouldDirty: true });
  const justChecked = under.data;
  // The check just made becomes the choice once it's ready.
  const readyId = justChecked?.state === "ready" ? justChecked.id : null;
  useEffect(() => {
    if (readyId !== null) {
      form.setValue("imageId", readyId, { shouldValidate: true, shouldDirty: true });
    }
  }, [form, readyId]);

  const onCheck = () =>
    submit.mutate(
      {
        reference: reference.trim(),
        registryUsername: isPrivate ? username.trim() : undefined,
        registryToken: isPrivate ? token : undefined,
        idempotencyKey,
      },
      {
        onSuccess: (image) => {
          setCheckingId(image.id);
          setToken("");
          setIdempotencyKey(crypto.randomUUID());
        },
      },
    );

  let body;
  if (images.isPending) {
    body = <Skeleton className="h-36 w-full" />;
  } else if (images.isError) {
    body = (
      <ErrorState
        error={images.error}
        what="your images"
        onRetry={() => void images.refetch()}
        retrying={images.isFetching}
      />
    );
  } else if (!images.data.canUse) {
    body = (
      <Banner tone="info">
        {images.data.whyNot}
        {images.data.enabled && (
          <>
            {" "}
            <Link href="/hosting/payout-details/" className="font-semibold underline">
              Give your PAN
            </Link>
          </>
        )}
      </Banner>
    );
  } else {
    const ready = images.data.images.filter((image) => image.state === "ready");
    body = (
      <div className="flex flex-col gap-4">
        {ready.length > 0 && (
          <div role="group" aria-label="Your checked images" className="flex flex-col gap-2">
            {ready.map((image) => (
              <Option
                key={image.id}
                icon={Package}
                title={<span className="break-all">{image.reference}</span>}
                detail={[
                  IMAGE_STATES[image.state],
                  image.result ? imageSize(image.result.compressedBytes) : null,
                  image.result?.cudaNeeded ? `CUDA ${image.result.cudaNeeded}` : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
                selected={chosen === image.id}
                onClick={() => choose(image.id)}
              />
            ))}
          </div>
        )}
        <div role="group" className="flex flex-col gap-1" aria-label="Check an image">
          <Field
            label={ready.length > 0 ? "Or check another image" : "Your image"}
            placeholder="ghcr.io/you/train:v3"
            autoComplete="off"
            spellCheck={false}
            value={reference}
            onChange={(event) => setReference(event.target.value)}
            hint="A tag or a digest, from Docker Hub, GHCR, quay.io, NGC or another public registry. It must be linux/amd64."
          />
          <label className="flex items-center gap-2 pb-3 text-small">
            <input
              type="checkbox"
              checked={isPrivate}
              onChange={(event) => setPrivate(event.target.checked)}
            />
            It's in a private registry
          </label>
          {isPrivate && (
            <div className="flex flex-col gap-1">
              <Field
                label="User name"
                autoComplete="off"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
              <Field
                label="Read-only token"
                type="password"
                autoComplete="off"
                value={token}
                onChange={(event) => setToken(event.target.value)}
                hint="One that can only read this repository. It's kept sealed, used only to download your image, and deleted after 30 days unused."
              />
            </div>
          )}
          <div className="flex flex-col items-start gap-2">
            <Button
              variant="secondary"
              onClick={onCheck}
              busy={submit.isPending}
              disabled={reference.trim() === "" || (isPrivate && (username.trim() === "" || token === ""))}
            >
              Check it
            </Button>
            {submit.isError && (
              <p role="alert" className="text-small text-danger">
                {errorSentence(submit.error, "the check")}
              </p>
            )}
          </div>
        </div>
        {justChecked && (
          <div className="rounded-control bg-fill px-4 py-3 tiled:bg-ground">
            <p className="pb-1 text-small font-semibold break-all">{justChecked.reference}</p>
            <ImageFindings
              image={justChecked}
              vulnerabilitiesHref={`/account/images/?id=${justChecked.id}`}
            />
            {!checking(justChecked) && justChecked.state !== "ready" && (
              <p className="pt-1 text-small text-text-muted">
                Fix it and check it again, or choose another image.
              </p>
            )}
          </div>
        )}
      </div>
    );
  }

  return (
    <Tile as="section" id={`input-${ID}`} aria-labelledby={ID} className="flex flex-col gap-3">
      <StepTitle
        id={ID}
        number={number}
        help="We check your image before it reaches anyone's machine, then run it in the same sandbox as every job."
      >
        Your image
      </StepTitle>
      {body}
      {error && (
        <p role="alert" className="text-small text-danger">
          {error}
        </p>
      )}
    </Tile>
  );
}
