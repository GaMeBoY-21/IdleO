import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { InputFact } from "@/lib/inputs";
import { data } from "@/test/fixtures";
import { Finding } from "./Finding";
import { YourRequestTile } from "./laptop/YourRequestTile";

const GB = 1024 ** 3;
const inputs: InputFact[] = [
  { name: "model", label: "Your model", link: false, filename: "my-llm", bytes: 4.2 * GB, files: 9 },
  {
    name: "data",
    label: "Your training data",
    link: false,
    filename: "train.jsonl",
    bytes: 68 * 1024,
    files: 1,
  },
];
const template = data.templateList.find((candidate) => candidate.id === "qlora-finetune");

describe("a session with two inputs", () => {
  it("is asked for, on a phone, by each input's label with its own size", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <Finding
          session={data.foundWithTwoInputs}
          inputs={inputs}
          laptop={false}
          steps={null}
          request={null}
        />
      </QueryClientProvider>,
    );
    expect(screen.getByText("Your model · 4.2 GB · 9 files")).toBeInTheDocument();
    expect(screen.getByText("Your training data · 68 KB")).toBeInTheDocument();
  });

  it("is, on a laptop, a row for each input in Your request, the upload's name beside its size, and a link as a link", () => {
    const { rerender } = render(
      <YourRequestTile session={data.foundWithTwoInputs} template={template} inputs={inputs} />,
    );
    expect(screen.getByText("Your model")).toBeInTheDocument();
    expect(screen.getByText("my-llm · 4.2 GB · 9 files")).toBeInTheDocument();
    expect(screen.getByText("train.jsonl · 68 KB")).toBeInTheDocument();
    rerender(
      <YourRequestTile
        session={data.foundWithTwoInputs}
        template={template}
        inputs={[{ ...inputs[0]!, link: true, filename: null, bytes: null, files: null }, inputs[1]!]}
      />,
    );
    expect(screen.getByText("From your link")).toBeInTheDocument();
  });
});
