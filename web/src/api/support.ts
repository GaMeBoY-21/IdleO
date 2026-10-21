import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import type { components } from "./schema.gen";

export type SupportTicket = components["schemas"]["SupportTicket"];
export type SupportThread = components["schemas"]["SupportThread"];
export type PublicContact = components["schemas"]["PublicContact"];
export type NewTicket = components["schemas"]["NewTicket"];

export const supportKeys = {
  contact: ["contact"] as const,
  tickets: ["tickets"] as const,
  ticket: (id: string) => ["ticket", id] as const,
};

/** The topics and the grievance officer: the server's, the one list (ADR 0037). */
export function useContact(enabled = true) {
  return useQuery({
    queryKey: supportKeys.contact,
    enabled,
    staleTime: 10 * 60_000,
    queryFn: () => data(api.GET("/v1/public/contact")),
  });
}

/** The person's own tickets, newest first. */
export function useTickets(enabled = true) {
  return useQuery({
    queryKey: supportKeys.tickets,
    enabled,
    queryFn: () => data(api.GET("/v1/support/tickets")),
  });
}

export function useTicket(id: string, enabled = true) {
  return useQuery({
    queryKey: supportKeys.ticket(id),
    enabled: enabled && id !== "",
    queryFn: () => data(api.GET("/v1/support/tickets/{id}", { params: { path: { id } } })),
  });
}

/** Sends a message; the list then holds it. */
export function useOpenTicket() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: NewTicket) => data(api.POST("/v1/support/tickets", { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: supportKeys.tickets }),
  });
}

/** Adds to one of the person's tickets; the thread is what the server answers. */
export function useReplyTicket(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (message: string) =>
      data(api.POST("/v1/support/tickets/{id}/messages", { params: { path: { id } }, body: { message } })),
    onSuccess: (thread) => {
      client.setQueryData(supportKeys.ticket(id), thread);
      void client.invalidateQueries({ queryKey: supportKeys.tickets });
    },
  });
}
