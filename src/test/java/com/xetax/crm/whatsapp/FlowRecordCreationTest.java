package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.dto.RecordResponse;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.service.FieldService;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.data_manager.validator.*;
import com.xetax.crm.whatsapp.dto.FlowResponseView;
import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowResponseRepository;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A submitted Flow becoming a record. The record goes through the form's REAL
 * validation here: in production "Unknown field : PHONE" rejected every
 * submission, and a text "500000" for a number field would have been next.
 */
class FlowRecordCreationTest {

    private final List<FieldResponse> formFields = new ArrayList<>();
    private final List<Map<String, Object>> created = new ArrayList<>();
    private final Map<String, WhatsAppFlowResponse> byToken = new HashMap<>();
    private WhatsAppFlowResponseRepository responseRepository;
    private WhatsAppFlow flow;
    private WhatsAppFlowService service;

    /** What the real record service does with the data first. */
    private final DynamicValidationService validation = new DynamicValidationServiceImpl(
            new RequiredFieldValidator(), new UnknownFieldValidator(),
            new DefaultValueValidator(), new FieldTypeValidator());

    private static FieldResponse field(String key, FieldType type, boolean required) {
        FieldResponse f = new FieldResponse();
        f.setFieldKey(key);
        f.setLabel(key);
        f.setFieldType(type);
        f.setRequired(required);
        return f;
    }

    @BeforeEach
    void setUp() {
        // The lead form behind flow_xeta.
        formFields.addAll(List.of(
                field("name", FieldType.TEXT, true),
                field("email", FieldType.EMAIL, false),
                field("phone", FieldType.PHONE, false),
                field("budget", FieldType.NUMBER, false),
                field("notes", FieldType.TEXTAREA, false),
                field("source", FieldType.SELECT, false)));

        FormService formService = mock(FormService.class);
        when(formService.getById(3L)).thenReturn(FormResponse.builder().id(3L).name("Leads").slug("leads").build());
        FieldService fieldService = mock(FieldService.class);
        when(fieldService.getAll(3L)).thenAnswer(inv -> formFields);

        RecordService recordService = mock(RecordService.class);
        when(recordService.create(eq("leads"), any())).thenAnswer(inv -> {
            RecordRequest request = inv.getArgument(1);
            List<FormField> entities = formFields.stream().map(f -> FormField.builder()
                    .fieldKey(f.getFieldKey()).label(f.getLabel()).fieldType(f.getFieldType())
                    .required(f.getRequired()).build()).toList();
            validation.validate(request, entities);        // throws exactly as production does
            created.add(request.getData());
            return RecordResponse.builder().id("rec-" + created.size()).build();
        });

        WhatsAppConfigService configService = mock(WhatsAppConfigService.class);
        when(configService.defaultCountryCode()).thenReturn("91");
        when(configService.currentUserId()).thenReturn("owner-1");

        flow = WhatsAppFlow.builder().ownerUserId("owner-1").name("flow_xeta").formId(3L).build();
        flow.setId(5L);
        WhatsAppFlowRepository flowRepository = mock(WhatsAppFlowRepository.class);
        when(flowRepository.findById(5L)).thenReturn(Optional.of(flow));

        responseRepository = mock(WhatsAppFlowResponseRepository.class);
        when(responseRepository.findByFlowToken(any()))
                .thenAnswer(inv -> Optional.ofNullable(byToken.get(inv.<String>getArgument(0))));
        when(responseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new WhatsAppFlowService(flowRepository, responseRepository, configService, null, null,
                new ObjectMapper(), null, formService, fieldService, null, recordService);
    }

    /** Exactly what PUNIT SHARMA submitted. */
    private static final String PUNIT = """
            {"flow_token":"flw_5_a","budget":"500000","email":"test@gmail.com","name":"PUNIT SHARMA",
             "notes":"Testing of the flows","phone":"9034908545","source":"WhatsApp"}""";

    private WhatsAppFlowResponse waiting(String token) {
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder()
                .ownerUserId("owner-1").flowId(5L).flowToken(token).note("waiting").build();
        byToken.put(token, row);
        return row;
    }

    @Test
    void theRealSubmissionNowBecomesARecord() {
        WhatsAppFlowResponse row = waiting("flw_5_a");
        service.recordSubmission("owner-1", "flw_5_a", "919034908543", 1L, PUNIT);

        assertEquals("Submitted — record created", row.getNote(), row.getNote());
        assertEquals("rec-1", row.getRecordId());
        Map<String, Object> data = created.get(0);
        assertFalse(data.containsKey("PHONE"), "never a key the form does not have");
        assertEquals("9034908545", data.get("phone"), "the number the customer typed wins");
        assertEquals(0, new BigDecimal("500000").compareTo((BigDecimal) data.get("budget")));
        assertEquals("PUNIT SHARMA", data.get("name"));
        assertEquals("WhatsApp", data.get("source"));
    }

    @Test
    void theChatNumberFillsThePhoneFieldWhenTheFlowDidNotAskForIt() {
        waiting("flw_5_b");
        service.recordSubmission("owner-1", "flw_5_b", "919034908543", 1L,
                "{\"flow_token\":\"flw_5_b\",\"name\":\"Rohan\"}");
        assertEquals("9034908543", created.get(0).get("phone"));
    }

    @Test
    void aFormWithoutAPhoneFieldGetsNoPhone() {
        formFields.removeIf(f -> f.getFieldType() == FieldType.PHONE);
        waiting("flw_5_c");
        service.recordSubmission("owner-1", "flw_5_c", "919034908543", 1L,
                "{\"flow_token\":\"flw_5_c\",\"name\":\"Rohan\"}");
        assertEquals(Map.of("name", "Rohan"), created.get(0));
    }

    @Test
    void anAnswerTheFormNoLongerHasIsLeftOnTheSubmissionOnly() {
        WhatsAppFlowResponse row = waiting("flw_5_d");
        service.recordSubmission("owner-1", "flw_5_d", "919034908543", 1L,
                "{\"flow_token\":\"flw_5_d\",\"name\":\"Rohan\",\"old_field\":\"x\"}");
        assertNotNull(row.getRecordId());
        assertFalse(created.get(0).containsKey("old_field"));
        assertTrue(row.getAnswersJson().contains("old_field"), "the answer itself is kept");
    }

    @Test
    void aMissingRequiredAnswerStillExplainsItself() {
        WhatsAppFlowResponse row = waiting("flw_5_e");
        service.recordSubmission("owner-1", "flw_5_e", "919034908543", 1L,
                "{\"flow_token\":\"flw_5_e\",\"email\":\"a@b.com\"}");
        assertNull(row.getRecordId());
        assertTrue(row.getNote().contains("name is required"), row.getNote());
    }

    /* ---------------------------------------------------------------- retry */

    @Test
    void aFailedSubmissionCanBeRetried() {
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder()
                .ownerUserId("owner-1").flowId(5L).customerPhone("919034908543")
                .answersJson(PUNIT.replace("\"flow_token\":\"flw_5_a\",", ""))
                .note("Submitted — could not create the record: Unknown field : PHONE").build();
        row.setId(40L);
        when(responseRepository.findById(40L)).thenReturn(Optional.of(row));

        FlowResponseView view = service.retryRecord(40L);

        assertEquals("rec-1", view.getRecordId());
        assertEquals("Submitted — record created", view.getNote());
        assertEquals("9034908545", created.get(0).get("phone"));
    }

    @Test
    void retryingTwiceDoesNotMakeASecondRecord() {
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder()
                .ownerUserId("owner-1").flowId(5L).recordId("rec-9").answersJson(PUNIT).build();
        row.setId(41L);
        when(responseRepository.findById(41L)).thenReturn(Optional.of(row));
        assertEquals("rec-9", service.retryRecord(41L).getRecordId());
        assertTrue(created.isEmpty());
    }

    @Test
    void anotherWorkspacesSubmissionCannotBeRetried() {
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder()
                .ownerUserId("someone-else").flowId(5L).answersJson(PUNIT).build();
        row.setId(42L);
        when(responseRepository.findById(42L)).thenReturn(Optional.of(row));
        assertThrows(com.xetax.crm.common.exception.ResourceNotFoundException.class, () -> service.retryRecord(42L));
    }

    @Test
    void aFlowWithoutAFormCannotMakeRecords() {
        flow.setFormId(null);
        WhatsAppFlowResponse row = WhatsAppFlowResponse.builder()
                .ownerUserId("owner-1").flowId(5L).answersJson(PUNIT).build();
        row.setId(43L);
        when(responseRepository.findById(43L)).thenReturn(Optional.of(row));
        assertThrows(BadRequestException.class, () -> service.retryRecord(43L));
    }
}
