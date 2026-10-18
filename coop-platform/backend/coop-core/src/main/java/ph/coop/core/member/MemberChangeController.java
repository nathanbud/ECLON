package ph.coop.core.member;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of {@link MemberChangeService}: submit, look at, approve, reject and withdraw member changes. */
@RestController
@RequestMapping("/api")
class MemberChangeController {

    record Capabilities(boolean edit, boolean approve) {}

    record Decision(String note) {}

    private final MemberChangeService changes;

    MemberChangeController(MemberChangeService changes) {
        this.changes = changes;
    }

    private static boolean officer(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_MEMBERSHIP_OFFICER"));
    }

    private static boolean canEdit(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("PERM_MEMBER_EDIT"));
    }

    /** What this person may do, so the screen only offers it. */
    @GetMapping("/capabilities")
    Capabilities capabilities(Authentication auth) {
        boolean on = changes.editAvailable();
        return new Capabilities(on && canEdit(auth), on && officer(auth));
    }

    /** Proposes changes to a client. The client is not updated until a Membership Officer approves. */
    @PostMapping("/members/{branchCode}/{clientId}/changes")
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    MemberChangeService.Change submit(@PathVariable int branchCode, @PathVariable int clientId,
                                      @RequestBody MemberChangeService.SubmitRequest req, Authentication auth) {
        return changes.submit(branchCode, clientId, req, auth.getName());
    }

    @GetMapping("/members/{branchCode}/{clientId}/changes/pending")
    ResponseEntity<MemberChangeService.Change> pending(@PathVariable int branchCode, @PathVariable int clientId) {
        MemberChangeService.Change c = changes.pendingFor(branchCode, clientId);
        return c == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(c);
    }

    @GetMapping("/changes")
    List<MemberChangeService.Change> list(@RequestParam(defaultValue = "PENDING") String status, Authentication auth) {
        return changes.list(status.toUpperCase(), auth.getName(), officer(auth));
    }

    @GetMapping("/changes/count")
    Map<String, Integer> count(Authentication auth) {
        return Map.of("pending", changes.countPending(auth.getName(), officer(auth)));
    }

    @PostMapping("/changes/{id}/approve")
    MemberChangeService.Change approve(@PathVariable long id, @RequestBody(required = false) Decision body, Authentication auth) {
        return changes.approve(id, auth.getName(), officer(auth), body == null ? null : body.note());
    }

    @PostMapping("/changes/{id}/reject")
    MemberChangeService.Change reject(@PathVariable long id, @RequestBody(required = false) Decision body, Authentication auth) {
        return changes.reject(id, auth.getName(), officer(auth), body == null ? null : body.note());
    }

    @PostMapping("/changes/{id}/withdraw")
    MemberChangeService.Change withdraw(@PathVariable long id, Authentication auth) {
        return changes.withdraw(id, auth.getName());
    }
}
