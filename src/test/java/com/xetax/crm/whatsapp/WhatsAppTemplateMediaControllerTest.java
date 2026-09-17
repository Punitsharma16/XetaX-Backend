package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.controller.WhatsAppTemplateMediaController;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateMediaService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateMediaService.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Meta fetches the header file from this link on every send — it must answer with the file itself. */
class WhatsAppTemplateMediaControllerTest {

    private WhatsAppTemplateMediaService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(WhatsAppTemplateMediaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WhatsAppTemplateMediaController(service)).build();
    }

    @Test
    void aLinkWithItsExtensionServesTheFile() throws Exception {
        when(service.load("abc123.jpg")).thenReturn(
                Optional.of(new StoredFile(new byte[]{1, 2, 3}, "image/jpeg", "diwali.jpg")));

        mvc.perform(get("/api/public/whatsapp/template-media/abc123.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"diwali.jpg\""));

        verify(service).load("abc123.jpg");
    }

    @Test
    void anUnknownLinkIsNotFound() throws Exception {
        when(service.load(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/api/public/whatsapp/template-media/nothing.png"))
                .andExpect(status().isNotFound());
    }
}
