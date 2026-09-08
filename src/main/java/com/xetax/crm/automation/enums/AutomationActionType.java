package com.xetax.crm.automation.enums;

public enum AutomationActionType {

    UPDATE_FIELD,

    CHANGE_STAGE,

    ASSIGN_USER,

    /**
     * Sends an email built from the action's emailSubject / emailMessage.
     * formField points at the record field that holds the recipient address,
     * and both texts may reference record values as {fieldKey}.
     */
    SEND_EMAIL,

    /**
     * Adds action.value to the numeric field in formField. The amount is
     * signed: "10" increases, "-5" decreases; blank defaults to 1.
     */
    ADJUST_FIELD,

    /**
     * Sends a WhatsApp text message built from emailMessage (the shared
     * message-template column). formField points at the record field holding
     * the customer's phone number; the text may reference record values as
     * {fieldKey}. Requires the form owner to have WhatsApp connected.
     */
    SEND_WHATSAPP,

    /**
     * Personalizes a stored document ({{fieldKey}} placeholders filled from
     * the record) and sends it on the chosen channel. documentId picks the
     * document, channel is WHATSAPP or EMAIL, formField holds the recipient
     * phone/email field, emailSubject/emailMessage become the subject/body
     * (WhatsApp: emailMessage is the caption).
     */
    SEND_DOCUMENT

}
