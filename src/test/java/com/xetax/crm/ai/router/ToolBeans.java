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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The real tool classes, instantiated with null collaborators.
 *
 * <p>The router only ever reads their {@code @Tool} annotations — it never
 * calls them — so the services behind them are irrelevant here, and going
 * through the real classes rather than a hand-kept list of names is the whole
 * point: a tool renamed or added upstream shows up in these tests.
 *
 * <p>Mockito mocks would not do. Method annotations are not inherited, so a
 * generated subclass carries no {@code @Tool} and the registry would come back
 * empty and the tests would pass on nothing.
 */
public final class ToolBeans {

    private ToolBeans() {
    }

    public static List<Class<?>> classes() {
        return List.of(FormTools.class, FormFieldTools.class, StageTools.class,
                AutomationTools.class, RecordTools.class, ContactTools.class,
                WhatsAppTools.class, MeetingTools.class, TeamTools.class, AgentTools.class);
    }

    public static List<Object> instances() {
        List<Object> beans = new ArrayList<>();
        for (Class<?> type : classes()) {
            beans.add(instantiate(type));
        }
        return beans;
    }

    /** Widest declared constructor, every argument null (or a primitive zero). */
    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> widest = java.util.Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            widest.setAccessible(true);
            Class<?>[] parameters = widest.getParameterTypes();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = defaultValue(parameters[i]);
            }
            return widest.newInstance(arguments);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not instantiate " + type.getName(), e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0;
    }
}
