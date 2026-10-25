package com.example.shop.payment.webhook;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PspWebhookEventRepository extends JpaRepository<PspWebhookEvent, String> {

    List<PspWebhookEvent> findAllByOrderByReceivedAtDesc(Pageable page);

    /**
     * Atomic "store if new". Not save(): for an entity with an assigned id that is a merge, which would quietly UPDATE the row a
     * concurrent delivery just inserted and report it as new again.
     *
     * @return 1 if this call stored the event, 0 if it was already there
     */
    @Transactional   // a modifying query needs one, and the controller deliberately does not hold a transaction open
    @Modifying
    @Query(value = "insert into psp_webhook_event (event_id, type, payload, received_at) values (:id, :type, :payload, :at) "
            + "on conflict (event_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("type") String type, @Param("payload") String payload,
                       @Param("at") Instant at);
}
