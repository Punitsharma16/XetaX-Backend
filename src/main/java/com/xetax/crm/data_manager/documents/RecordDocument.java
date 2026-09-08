package com.xetax.crm.data_manager.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@CompoundIndexes({
        @CompoundIndex(
                name = "form_stage_idx",
                def = "{'formId':1,'stageId':1}"
        ),
        @CompoundIndex(
                name = "form_created_idx",
                def = "{'formId':1,'createdAt':-1}"
        )
})
public class RecordDocument {

    @Id
    private String id;

    @Indexed
    private Long formId;

    @Indexed
    private Long stageId;

    /** Sub-state inside the current stage (optional — null when the stage has no statuses). */

    @Indexed
    private Long userId;

    @Indexed
    private String createdBy;

    @Indexed
    private LocalDateTime createdAt;

    @Indexed
    private LocalDateTime updatedAt;

    /** Team member (uuid) ye record assigned hai — transfer isi ko badalta hai. */
    @Indexed
    private String assignedTo;

    private Map<String, Object> data;
}
