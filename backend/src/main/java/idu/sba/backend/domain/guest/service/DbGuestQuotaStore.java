package idu.sba.backend.domain.guest.service;

import idu.sba.backend.domain.guest.repository.GuestQuotaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "guest.quota.store", havingValue = "db")
public class DbGuestQuotaStore implements GuestQuotaStore {
    private final GuestQuotaRepository repository;
    private final TransactionTemplate transaction;

    public DbGuestQuotaStore(GuestQuotaRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public Optional<Reservation> tryAcquire(String guestToken) {
        // UPSERT의 행 잠금은 커밋까지 유지된다. AI를 기다리는 동안에는 잠그지 않는다.
        return transaction.execute(status -> {
            repository.prepareWindow(guestToken, UUID.randomUUID().toString(), TRIAL_WINDOW.toSeconds());
            if (!repository.incrementBelowLimit(guestToken, TRIAL_LIMIT)) return Optional.empty();
            return Optional.of(new Reservation(guestToken, repository.windowId(guestToken)));
        });
    }

    @Override
    public void refund(Reservation reservation) {
        transaction.executeWithoutResult(status ->
                repository.refund(reservation.guestToken(), reservation.windowId()));
    }
}
