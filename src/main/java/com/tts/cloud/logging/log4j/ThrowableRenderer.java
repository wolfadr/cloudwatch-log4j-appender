package com.tts.cloud.logging.log4j;

import org.apache.log4j.spi.LoggingEvent;

/**
 * Created by WolfAn on 8/29/2016.
 */
public interface ThrowableRenderer {

    void renderToStringBuilder(StringBuilder sb, LoggingEvent event);
}
