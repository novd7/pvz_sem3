package pvz_mirea;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
class PvzController {
    private final Accounts accounts;
    private final PickupPoints pickupPoints;
    private final Receipts receipts;
    private final Snapshots snapshots;

    PvzController(Accounts accounts, PickupPoints pickupPoints, Receipts receipts, Snapshots snapshots) {
        this.accounts = accounts;
        this.pickupPoints = pickupPoints;
        this.receipts = receipts;
        this.snapshots = snapshots;
    }

    record Login(@NotBlank @Size(max = 100) String login, @NotBlank @Size(max = 200) String password) {}
    record Receipt(@NotBlank @Size(max = 100) String code, @NotNull @Positive Long cellId) {}

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    Accounts.User login(HttpServletRequest request, @Valid @RequestBody Login login) {
        return accounts.login(request, login.login(), login.password());
    }

    @GetMapping("/auth/me")
    Accounts.User me(HttpServletRequest request) {
        return accounts.current(request);
    }

    @PostMapping(path = "/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        accounts.logout(request);
    }

    @GetMapping("/pvz")
    List<PickupPoints.Point> points(HttpServletRequest request) {
        return pickupPoints.points(accounts.current(request));
    }

    @GetMapping("/overview")
    PickupPoints.Overview overview(HttpServletRequest request) {
        return pickupPoints.overview(accounts.current(request));
    }

    @GetMapping("/cells")
    List<PickupPoints.Cell> cells(HttpServletRequest request) {
        return pickupPoints.cells(accounts.current(request));
    }

    @GetMapping("/shipments/{code}")
    PickupPoints.Shipment shipment(HttpServletRequest request, @PathVariable @Size(max = 100) String code) {
        return pickupPoints.shipment(accounts.current(request), code);
    }

    @PostMapping(path = "/receipts", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> receipt(HttpServletRequest request, @Valid @RequestBody Receipt receipt,
                                   @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String key) {
        Accounts.User user = accounts.current(request);
        user.requireEmployee();
        try {
            String result = receipts.receive(user.id(), key, receipt.code(), receipt.cellId());
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(result);
        } catch (ApiError rejected) {
            if (rejected.getStatusCode().value() == 409 || rejected.getStatusCode().value() == 404) {
                receipts.recordRejection(user, rejected);
            }
            throw rejected;
        }
    }

    @GetMapping(path = "/exports/operations", produces = "application/zip")
    ResponseEntity<byte[]> export(HttpServletRequest request) {
        var snapshot = snapshots.export(accounts.current(request).id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=pvz-" + snapshot.id() + ".zip")
                .header("X-Snapshot-Id", snapshot.id().toString())
                .header("X-Snapshot-Time", snapshot.time())
                .body(snapshot.bytes());
    }
}
