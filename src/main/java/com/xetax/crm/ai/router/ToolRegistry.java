package com.xetax.crm.ai.router;

import com.xetax.crm.agent.tools.AgentTools;
import com.xetax.crm.ai.tools.AutomationTools;
import com.xetax.crm.ai.tools.ContactTools;
import com.xetax.crm.ai.tools.FormFieldTools;
import com.xetax.crm.ai.tools.FormTools;
import com.xetax.crm.ai.tools.RecordTools;
import com.xetax.crm.ai.tools.StageTools;
import com.xetax.crm.meeting.tools.MeetingTools;
import com.xetax.crm.team.tools.TeamTools;
import com.xetax.crm.whatsapp.tools.WhatsAppTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every {@code @Tool} method in the panel, reflected once at startup and
 * indexed by name.
 *
 * <p>Two reasons this exists rather than handing POJOs to the ChatClient on
 * each call. First, {@code ChatClientRequestSpec.tools(...)} APPENDS to the
 * builder's defaults — it does not replace them (the javadoc claims
 * otherwise; {@code DefaultChatClient.tools(Object...)} is the truth), so the
 * only way to send fewer tools is to register none as defaults and pass an
 * explicit list per request. Second, {@code ToolCallbacks.from(...)} walks the
 * class by reflection; doing that once at boot instead of on every message
 * keeps the routing free.
 *
 * <p>Iteration order is the registration order and is stable across requests,
 * which keeps the serialized tool block identical between calls that route
 * the same way — the shape prompt caching needs.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** Tool name -> callback, in registration order. */
    private final Map<String, ToolCallback> byName = new LinkedHashMap<>();

    /** Names sent with every request: the core set plus anything unrouted. */
    private final Set<String> alwaysAvailable = new LinkedHashSet<>();

    // Two constructors: tell Spring which one wires the container.
    @Autowired
    public ToolRegistry(FormTools formTools,
                        FormFieldTools formFieldTools,
                        StageTools stageTools,
                        AutomationTools automationTools,
                        RecordTools recordTools,
                        ContactTools contactTools,
                        WhatsAppTools whatsAppTools,
                        MeetingTools meetingTools,
                        TeamTools teamTools,
                        AgentTools agentTools) {
        this(List.of(formTools, formFieldTools, stageTools, automationTools, recordTools,
                contactTools, whatsAppTools, meetingTools, teamTools, agentTools));
    }

    /** Test seam: build a registry from an explicit set of tool-bearing beans. */
    public ToolRegistry(List<Object> toolBeans) {
        for (ToolCallback callback : ToolCallbacks.from(toolBeans.toArray())) {
            String name = callback.getToolDefinition().name();
            ToolCallback clash = this.byName.put(name, callback);
            if (clash != null) {
                // Backstop. ToolCallbacks.from already refuses duplicate names
                // across everything handed to it in one call, so this only
                // fires if that ever stops being true — and one tool silently
                // shadowing another is not something to discover in
                // production.
                throw new IllegalStateException(
                        "Duplicate AI tool name '" + name + "' — tool names must be unique");
            }
        }

        this.alwaysAvailable.addAll(ToolDomain.ALWAYS_AVAILABLE);

        Set<String> missing = new TreeSet<>();
        for (String name : ToolDomain.ALWAYS_AVAILABLE) {
            if (!this.byName.containsKey(name)) {
                missing.add(name);
            }
        }
        for (ToolDomain domain : ToolDomain.values()) {
            for (String name : domain.tools()) {
                if (!this.byName.containsKey(name)) {
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            // A routed name with no tool behind it is a capability the
            // assistant quietly lost — usually a rename. ToolRoutingMapTest
            // catches this in CI; failing here is the backstop.
            throw new IllegalStateException(
                    "ToolDomain routes to tools that do not exist: " + missing
                            + " — update com.xetax.crm.ai.router.ToolDomain");
        }

        Set<String> routed = new LinkedHashSet<>(ToolDomain.ALWAYS_AVAILABLE);
        for (ToolDomain domain : ToolDomain.values()) {
            routed.addAll(domain.tools());
        }
        Set<String> unrouted = new LinkedHashSet<>(this.byName.keySet());
        unrouted.removeAll(routed);
        if (!unrouted.isEmpty()) {
            // A tool added later but not placed in a domain would otherwise be
            // unreachable. Fail SAFE, not fast: carry it on every request and
            // say so loudly, so a new tool works on day one and still gets a
            // home.
            log.warn("AI tools in no ToolDomain, sent with every request: {} "
                    + "— add them to com.xetax.crm.ai.router.ToolDomain", unrouted);
            this.alwaysAvailable.addAll(unrouted);
        }
    }

    /**
     * Callbacks for these domains, plus the always-available set.
     *
     * @param domains the routed domains; empty means only the always-available set
     */
    public List<ToolCallback> forDomains(Collection<ToolDomain> domains) {
        Set<String> wanted = new LinkedHashSet<>(this.alwaysAvailable);
        for (ToolDomain domain : domains) {
            wanted.addAll(domain.tools());
        }
        List<ToolCallback> selected = new ArrayList<>(wanted.size());
        // Walk the registry, not the wanted set, so ordering stays stable.
        for (Map.Entry<String, ToolCallback> entry : this.byName.entrySet()) {
            if (wanted.contains(entry.getKey())) {
                selected.add(entry.getValue());
            }
        }
        return selected;
    }

    /** Every tool, in registration order — what an unrouted request gets. */
    public List<ToolCallback> all() {
        return List.copyOf(this.byName.values());
    }

    /** Names of every registered tool. */
    public Set<String> names() {
        return Set.copyOf(this.byName.keySet());
    }
}
