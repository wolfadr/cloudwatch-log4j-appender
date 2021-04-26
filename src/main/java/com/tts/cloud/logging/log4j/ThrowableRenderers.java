package com.tts.cloud.logging.log4j;

import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

/**
 * Created by WolfAn on 8/29/2016.
 */
public enum ThrowableRenderers implements ThrowableRenderer {

    NONE("none") {
        @Override
        protected void renderThrowable(StringBuilder sb, Throwable throwable, String threadName) { }
    },

    FIRST_ELEMENT("first") {
        @Override
        protected void renderThrowable(StringBuilder sb, Throwable throwable, String threadName) {
            Header.NORMAL.render(sb, throwable, threadName);
            renderStackTraceElements(sb, throwable.getStackTrace(), 1);
        }
    },

    FIRST_ELEMENT_PLUS_CAUSES("first+causes") {
        @Override
        protected void renderThrowable(StringBuilder sb, Throwable throwable, String threadName) {
            Header.NORMAL.render(sb, throwable, threadName);
            renderStackTraceElements(sb, throwable.getStackTrace(), 1);
            renderCauses(sb, throwable, 1);
        }
    },

    FULL("full") {
        @Override
        protected void renderThrowable(StringBuilder sb, Throwable throwable, String threadName) {
            Header.NORMAL.render(sb, throwable, threadName);
            renderStackTraceElements(sb, throwable.getStackTrace(), Integer.MAX_VALUE);
        }
    },

    FULL_PLUS_CAUSES("full+causes") {
        @Override
        protected void renderThrowable(StringBuilder sb, Throwable throwable, String threadName) {
            Header.NORMAL.render(sb, throwable, threadName);
            renderStackTraceElements(sb, throwable.getStackTrace(), Integer.MAX_VALUE);
            renderCauses(sb, throwable, Integer.MAX_VALUE);
        }
    };

    private String type;
    private ThrowableRenderers(String type) {
        this.type = type;
    }

    public static ThrowableRenderers of(String type) {
        ThrowableRenderers[] values = ThrowableRenderers.values();
        for(ThrowableRenderers appender : values) {
            if(appender.type.equalsIgnoreCase(type)) {
                return appender;
            }
        }
        return null;
    }

    public void renderToStringBuilder(StringBuilder sb, LoggingEvent event) {

        ThrowableInformation throwableInformation = event.getThrowableInformation();
        if(throwableInformation == null) {
            return;
        }

        renderThrowable(sb, throwableInformation.getThrowable(), event.getThreadName());
    }

    protected abstract void renderThrowable(StringBuilder sb, Throwable throwable, String threadName);

    protected void renderCauses(StringBuilder sb, Throwable throwable, int numStackTraceElements) {
        Throwable cause = throwable.getCause();
        while(cause != null) {
            Header.CAUSE.render(sb, cause, null);
            renderStackTraceElements(sb, cause.getStackTrace(), numStackTraceElements);
            cause = cause.getCause();
        }
    }

    protected void renderStackTraceElements(StringBuilder sb, StackTraceElement[] stackTraceElements, int n) {
        for(int i = 0; i < n && i < stackTraceElements.length; i++) {
            sb.append("        at ").append(stackTraceElements[i].toString()).append("\n");
        }
    }

    private static enum Header {

        NORMAL {
            @Override
            protected void render(StringBuilder sb, Throwable throwable, String threadName) {
                sb.append("Exception in thread \"").append(threadName).append("\" ");
                renderClassAndMessage(sb, throwable);
            }
        },

        CAUSE {
            @Override
            protected void render(StringBuilder sb, Throwable throwable, String threadName) {
                sb.append("Caused by ");
                renderClassAndMessage(sb, throwable);
            }
        };

        protected abstract void render(StringBuilder sb, Throwable throwable, String threadName);

        protected void renderClassAndMessage(StringBuilder sb, Throwable throwable) {
            sb.append(throwable.getClass().getCanonicalName());
            if(throwable.getLocalizedMessage() != null) {
                sb.append(": ").append(throwable.getLocalizedMessage());
            }
            sb.append("\n");
        }
    }
}
