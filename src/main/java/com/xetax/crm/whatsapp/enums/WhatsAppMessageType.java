package com.xetax.crm.whatsapp.enums;

/**
 * Every shape an inbound or outbound message can take. The media and
 * interactive kinds are what Meta's webhook actually sends; STICKER, LOCATION,
 * CONTACTS, ORDER, SYSTEM and UNSUPPORTED exist so a thread never silently
 * mislabels a customer's message as plain text.
 */
public enum WhatsAppMessageType {
    TEXT, TEMPLATE, IMAGE, DOCUMENT, VIDEO, AUDIO, STICKER,
    INTERACTIVE, REACTION, LOCATION, CONTACTS, ORDER, SYSTEM, UNSUPPORTED
}
