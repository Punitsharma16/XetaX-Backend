package com.xetax.crm.ai.router;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * What one message was routed to: the domains, the tools that go on the wire
 * and the system prompt that goes with them.
 *
 * @param domains      domains the message was matched to
 * @param tools        callbacks to hand the ChatClient for this request
 * @param systemPrompt system prompt covering exactly those domains
 * @param narrowed     false when the router fell back to the full tool set,
 *                     i.e. this request behaves exactly as it did before the
 *                     router existed
 */
public record RoutingDecision(Set<ToolDomain> domains,
                              List<ToolCallback> tools,
                              String systemPrompt,
                              boolean narrowed) {
}
