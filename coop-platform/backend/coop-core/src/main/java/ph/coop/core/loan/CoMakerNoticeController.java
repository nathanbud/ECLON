package ph.coop.core.loan;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What a member is bound to as a co-maker, for the note on their share capital and savings tabs. Read only. */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/comaker-holds")
class CoMakerNoticeController {

    private final CoMakerHolds holds;

    CoMakerNoticeController(CoMakerHolds holds) {
        this.holds = holds;
    }

    @GetMapping
    CoMakerHolds.Notice notice(@PathVariable int branchCode, @PathVariable int clientId) {
        return holds.noticeFor(branchCode, clientId);
    }
}
