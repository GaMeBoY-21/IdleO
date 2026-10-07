import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod/mini";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";

// zod/mini: the same checks as Zod's classic API, a fraction of the weight on the sign-in screen (ADR 0018).
const schema = z.object({
  email: z
    .string()
    .check(
      z.trim(),
      z.minLength(1, "Enter your email address."),
      z.regex(z.regexes.email, "That doesn't look like an email address."),
    ),
});

export type EmailValues = z.infer<typeof schema>;

/** One email field and its button; validated before anything is sent. */
export function EmailForm({
  label,
  submit,
  onSubmit,
  defaultEmail,
}: {
  label: string;
  submit: string;
  onSubmit: (values: EmailValues) => Promise<void>;
  defaultEmail?: string;
}) {
  const form = useForm<EmailValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: defaultEmail ?? "" },
  });
  return (
    <form
      noValidate
      onSubmit={(event) => void form.handleSubmit(onSubmit)(event)}
      className="flex flex-col gap-2"
    >
      <Field
        label={label}
        type="email"
        autoComplete="email"
        inputMode="email"
        error={form.formState.errors.email?.message}
        {...form.register("email")}
      />
      <Button type="submit" block busy={form.formState.isSubmitting}>
        {submit}
      </Button>
    </form>
  );
}
