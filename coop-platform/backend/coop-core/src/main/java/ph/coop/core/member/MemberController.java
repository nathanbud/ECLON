package ph.coop.core.member;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private static final int MAX_PAGE_SIZE = 100;
    // optional branch (with "-" or space), the client number, optional "-<check digit>"
    private static final Pattern MEMBER_ID = Pattern.compile("^(?:0*(\\d{1,4})[-\\s])?0*(\\d{1,9})(?:-\\d)?$");
    private static final Pattern REFERENCE_ID = Pattern.compile("^[A-Za-z]\\d{5,}$");

    private final MemberRepository members;

    public MemberController(MemberRepository members) {
        this.members = members;
    }

    @GetMapping
    public Page<Member> search(@RequestParam(defaultValue = "") String q,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by("lastName", "firstName"));
        String text = q.trim();

        // A member ID ("0001-0033189", "0001-0033189-9", "1-33189" or just "33189") or a reference ID ("A010352")
        // is an exact lookup, not a name search.
        Matcher id = MEMBER_ID.matcher(text);
        if (id.matches()) {
            int clientId = Integer.parseInt(id.group(2));
            List<Member> found = id.group(1) != null
                ? members.findByIdBranchCodeAndIdClientId(Integer.parseInt(id.group(1)), clientId)
                : members.findByIdClientId(clientId);
            return new PageImpl<>(found, pageable, found.size());
        }
        if (REFERENCE_ID.matcher(text).matches()) {
            List<Member> found = members.findByReferenceIdIgnoreCase(text);
            return new PageImpl<>(found, pageable, found.size());
        }
        return members.search(text, pageable);
    }

    @GetMapping("/{branchCode}/{clientId}")
    public ResponseEntity<Member> get(@PathVariable int branchCode, @PathVariable int clientId) {
        return members.findById(new Member.Id(branchCode, clientId))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
