package com.xetax.crm.dashboard.tools;

import com.xetax.crm.dashboard.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The one number-shaped answer the assistant could not give.
 *
 * <p>"Is mahine kaisa raha" used to make it walk several tools and add things
 * up itself, which is slow and gets the arithmetic wrong often enough to
 * matter. This is the same figure the Dashboard page draws, computed once by
 * the same service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardTools {

    private final DashboardService dashboardService;

    @Tool(description = """
            READ-ONLY. The whole workspace at a glance, exactly as the
            Dashboard page shows it: record and form counts, what came in
            recently, pipeline spread and the headline activity numbers. Use
            this for broad questions like "how is my business doing", "is
            mahine kaisa raha", "give me a summary" — one call instead of
            adding up several tools by hand.
            """)
    public Map<String, Object> getDashboardSummary() {
        try {
            return dashboardService.summary();
        }
        catch (Exception e) {
            String message = e.getMessage();
            return Map.of("error", message == null || message.isBlank()
                    ? "The dashboard summary could not be read." : message);
        }
    }
}
