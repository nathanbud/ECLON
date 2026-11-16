package ph.coop.core.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.workflow.WorkflowEngine.Definition;
import ph.coop.core.workflow.WorkflowEngine.Instance;
import ph.coop.core.workflow.WorkflowEngine.Step;
import ph.coop.core.workflow.WorkflowEngine.Waiting;

/**
 * The workflow engine's screens (guide phase 17): the configuration (who must approve what, from which amount; administrators change it, accountants read it), the list of items
 * waiting for a step the signed-in person can take, and taking that step. Items get into a chain from their own module's approve button, not from here.
 */
@RestController
@RequestMapping("/api/workflow")
class WorkflowController {

    record DefinitionIn(boolean active, List<Step> steps) {}

    record Decision(String note) {}

    record Options(List<String> roles, int maxSteps) {}

    private final WorkflowEngine engine;

    WorkflowController(WorkflowEngine engine) {
        this.engine = engine;
    }

    private static Set<String> roles(Authentication auth) {
        Set<String> out = new HashSet<>();
        for (var a : auth.getAuthorities()) out.add(a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority());
        return out;
    }

    @GetMapping("/definitions")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    List<Definition> definitions() {
        return engine.definitions();
    }

    @GetMapping("/options")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    Options options() {
        return new Options(WorkflowRules.STEP_ROLES.stream().sorted().toList(), WorkflowRules.MAX_STEPS);
    }

    @PutMapping("/definitions/{entityType}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Definition save(@PathVariable String entityType, @RequestBody DefinitionIn in, Authentication auth) {
        return engine.save(entityType, in.active(), in.steps(), auth.getName());
    }

    /** The items waiting for a step this person can take. */
    @GetMapping("/waiting")
    List<Waiting> waiting(Authentication auth) {
        return engine.waitingFor(roles(auth), auth.getName());
    }

    @GetMapping("/{entityType}/{entityId}")
    Instance instance(@PathVariable String entityType, @PathVariable String entityId) {
        return engine.instance(entityType, entityId);
    }

    @PostMapping("/{entityType}/{entityId}/approve")
    @Transactional
    Instance approve(@PathVariable String entityType, @PathVariable String entityId, @RequestBody(required = false) Decision in, Authentication auth) {
        return engine.decide(entityType, entityId, true, in == null ? null : in.note(), auth.getName(), roles(auth));
    }

    @PostMapping("/{entityType}/{entityId}/reject")
    @Transactional
    Instance reject(@PathVariable String entityType, @PathVariable String entityId, @RequestBody Decision in, Authentication auth) {
        if (in == null || in.note() == null || in.note().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why it is turned down.");
        return engine.decide(entityType, entityId, false, in.note(), auth.getName(), roles(auth));
    }
}
