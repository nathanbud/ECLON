package ph.coop.core.workflow;


import ph.coop.core.audit.AuditLog;import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.workflow.WorkflowRules.StepDef;

/**
 * The workflow engine (guide phase 17). A module keeps its own first approval and asks the engine whether that approval is enough ({@link #gate}). If the workflow for that kind of
 * item has extra steps whose limits the amount reaches, the item waits for them: each step is taken, through {@link #decide}, by a person holding the step's role who is not
 * the maker and has not approved an earlier step. When the last step agrees the engine calls the item's {@link WorkflowHost}; a rejection anywhere ends the item. The steps an
 * item needs are copied into it when its first approval happens, so changing the configuration later never changes something already under way. Every action is recorded.
 */
@Component
public class WorkflowEngine {

    private static final int BRANCH = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The answer to a module that has just recorded its own approval: finished, or waiting for the named extra step. */
    public record Gate(boolean done, String waitingFor) {}

    public record Action(int stepNo, String step, String actor, String decision, String note, LocalDateTime at) {}

    public record Instance(long id, String entityType, String entityId, BigDecimal amount, String requestedBy, String status, String waitingFor, String waitingRole, List<Action> actions) {}

    public record Waiting(long id, String entityType, String label, String entityId, String description, BigDecimal amount, String requestedBy, String step, String role, LocalDateTime since) {}

    public record Definition(String entityType, String label, boolean active, List<Step> steps) {}

    public record Step(int no, String name, String role, BigDecimal minAmount) {}

    private record Stored(int no, String name, String role) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final Map<String, WorkflowHost> hosts = new LinkedHashMap<>();

    WorkflowEngine(JdbcTemplate jdbc, Db db, Clock clock, List<WorkflowHost> hostList) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        for (WorkflowHost h : hostList) hosts.put(h.entityType(), h);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private WorkflowHost host(String type) {
        WorkflowHost h = hosts.get(type);
        if (h == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No workflow for " + type + ".");
        return h;
    }

    private static List<Stored> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Stored>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable workflow steps.", e);
        }
    }

    private static String write(List<Stored> steps) {
        try {
            return JSON.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the module's side -----------------------------------------------------------------------------------------

    /**
     * Called by a module when its own approver approves. Returns done when no extra approval is needed (no workflow, or the amount is under every limit); otherwise records the
     * module's approval as step 0, starts the chain and returns the step that is waiting. Calling it again while the item waits is refused.
     */
    public Gate gate(String entityType, String entityId, BigDecimal amount, String requestedBy, String approver) {
        host(entityType);
        List<Object[]> open = jdbc.query("SELECT steps_json, current_step FROM ecis_workflow_instance WHERE branch_code = ? AND entity_type = ? AND entity_id = ? AND status = 'WAITING' FOR UPDATE",
            (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, BRANCH, entityType, entityId);
        if (!open.isEmpty()) throw conflict("This has already been approved once and is waiting for " + parse((String) open.get(0)[0]).get((Integer) open.get(0)[1]).name() + ".");
        List<StepDef> all = definitionSteps(entityType, true);
        List<StepDef> needed = WorkflowRules.applicable(all, amount);
        if (needed.isEmpty()) return new Gate(true, null);
        List<Stored> stored = needed.stream().map((s) -> new Stored(s.no(), s.name(), s.role())).toList();
        long id = db.insertReturningId("""
                INSERT INTO ecis_workflow_instance (branch_code, entity_type, entity_id, amount, requested_by, steps_json, current_step, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, 'WAITING', ?)
                """, "id", BRANCH, entityType, entityId, amount, requestedBy, write(stored), Timestamp.valueOf(now()));
        action(id, 0, "First approval", approver, "APPROVED", null);
        return new Gate(false, stored.get(0).name());
    }

    /** What the item is waiting for (the step's name), or null when no workflow is holding it. */
    public String waitingFor(String entityType, String entityId) {
        List<Object[]> open = jdbc.query("SELECT steps_json, current_step FROM ecis_workflow_instance WHERE branch_code = ? AND entity_type = ? AND entity_id = ? AND status = 'WAITING'",
            (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, BRANCH, entityType, entityId);
        if (open.isEmpty()) return null;
        Stored s = parse((String) open.get(0)[0]).get((Integer) open.get(0)[1]);
        return s.name() + " (" + s.role().toLowerCase().replace('_', ' ') + ")";
    }

    /** The module withdrew or cancelled the item: close whatever was waiting on it. */
    public void cancel(String entityType, String entityId, String by, String note) {
        List<Long> open = jdbc.queryForList("SELECT id FROM ecis_workflow_instance WHERE branch_code = ? AND entity_type = ? AND entity_id = ? AND status = 'WAITING' FOR UPDATE", Long.class, BRANCH, entityType, entityId);
        for (Long id : open) {
            action(id, -1, "Cancelled", by, "CANCELLED", note);
            jdbc.update("UPDATE ecis_workflow_instance SET status = 'CANCELLED', closed_at = ? WHERE id = ?", Timestamp.valueOf(now()), id);
        }
    }

    private void action(long instance, int stepNo, String step, String actor, String decision, String note) {
        jdbc.update("INSERT INTO ecis_workflow_action (instance_id, step_no, step_name, actor, decision, note, at) VALUES (?, ?, ?, ?, ?, ?, ?)", instance, Math.max(stepNo, 0), step, actor, decision,
            note == null || note.isBlank() ? null : note.trim(), Timestamp.valueOf(now()));
    }

    // ---- the approvers' side ---------------------------------------------------------------------------------------

    private record Row(long id, BigDecimal amount, String requestedBy, List<Stored> steps, int current) {}

    private Row waitingRow(String entityType, String entityId, boolean lock) {
        List<Row> rows = jdbc.query("SELECT id, amount, requested_by, steps_json, current_step FROM ecis_workflow_instance WHERE branch_code = ? AND entity_type = ? AND entity_id = ? AND status = 'WAITING'" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> new Row(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3), parse(rs.getString(4)), rs.getInt(5)), BRANCH, entityType, entityId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing is waiting for an extra approval here.");
        return rows.get(0);
    }

    /** Takes the waiting step: approves it (the next step becomes current, or the item is finished through its host) or turns the item down. */
    public Instance decide(String entityType, String entityId, boolean approve, String note, String actor, Set<String> roles) {
        WorkflowHost host = host(entityType);
        Row r = waitingRow(entityType, entityId, true);
        Stored step = r.steps().get(r.current());
        if (!WorkflowRules.mayTake(roles, step.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This step (" + step.name() + ") is for the " + step.role().toLowerCase().replace('_', ' ') + " role.");
        List<String> earlier = jdbc.queryForList("SELECT actor FROM ecis_workflow_action WHERE instance_id = ? AND decision = 'APPROVED'", String.class, r.id());
        String problem = WorkflowRules.segregationProblem(r.requestedBy(), earlier, actor);
        if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);
        String clean = note == null || note.isBlank() ? null : note.trim();
        if (clean != null && clean.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long (255 characters at most).");
        if (!approve) {
            if (clean == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why it is turned down.");
            action(r.id(), step.no(), step.name(), actor, "REJECTED", clean);
            jdbc.update("UPDATE ecis_workflow_instance SET status = 'REJECTED', closed_at = ? WHERE id = ?", Timestamp.valueOf(now()), r.id());
            host.rejected(entityId, actor, step.name() + ": " + clean);
        } else {
            action(r.id(), step.no(), step.name(), actor, "APPROVED", clean);
            if (r.current() + 1 < r.steps().size()) {
                jdbc.update("UPDATE ecis_workflow_instance SET current_step = ? WHERE id = ?", r.current() + 1, r.id());
            } else {
                jdbc.update("UPDATE ecis_workflow_instance SET status = 'APPROVED', closed_at = ? WHERE id = ?", Timestamp.valueOf(now()), r.id());
                host.approved(entityId, actor);
            }
        }
        return instance(entityType, entityId);
    }

    /** The most recent chain of an item with what happened in it. */
    public Instance instance(String entityType, String entityId) {
        List<Object[]> rows = jdbc.query("SELECT id, amount, requested_by, steps_json, current_step, status FROM ecis_workflow_instance WHERE branch_code = ? AND entity_type = ? AND entity_id = ? ORDER BY id DESC LIMIT 1",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6)}, BRANCH, entityType, entityId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No workflow has been started for this.");
        Object[] r = rows.get(0);
        List<Stored> steps = parse((String) r[3]);
        List<Action> actions = jdbc.query("SELECT step_no, step_name, actor, decision, note, at FROM ecis_workflow_action WHERE instance_id = ? ORDER BY id",
            (rs, i) -> new Action(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toLocalDateTime()), r[0]);
        boolean waiting = r[5].equals("WAITING");
        Stored cur = waiting ? steps.get((Integer) r[4]) : null;
        return new Instance((Long) r[0], entityType, entityId, (BigDecimal) r[1], (String) r[2], (String) r[5], cur == null ? null : cur.name(), cur == null ? null : cur.role(), actions);
    }

    /** The items waiting for a step this person could take: right role, and not made or already approved by them. */
    public List<Waiting> waitingFor(Set<String> roles, String user) {
        List<Waiting> out = new ArrayList<>();
        jdbc.query("SELECT id, entity_type, entity_id, amount, requested_by, steps_json, current_step, created_at FROM ecis_workflow_instance WHERE branch_code = ? AND status = 'WAITING' ORDER BY id", (rs) -> {
            long id = rs.getLong(1);
            String type = rs.getString(2), eid = rs.getString(3);
            List<Stored> steps = parse(rs.getString(6));
            Stored cur = steps.get(rs.getInt(7));
            if (!WorkflowRules.mayTake(roles, cur.role()) || !hosts.containsKey(type)) return;
            List<String> earlier = jdbc.queryForList("SELECT actor FROM ecis_workflow_action WHERE instance_id = ? AND decision = 'APPROVED'", String.class, id);
            if (WorkflowRules.segregationProblem(rs.getString(5), earlier, user) != null) return;
            WorkflowHost h = hosts.get(type);
            out.add(new Waiting(id, type, h.label(), eid, h.describe(eid), rs.getBigDecimal(4), rs.getString(5), cur.name(), cur.role(), rs.getTimestamp(8).toLocalDateTime()));
        }, BRANCH);
        return out;
    }

    // ---- configuration ---------------------------------------------------------------------------------------------

    private List<StepDef> definitionSteps(String entityType, boolean activeOnly) {
        List<Long> id = jdbc.queryForList("SELECT id FROM ecis_workflow WHERE branch_code = ? AND entity_type = ?" + (activeOnly ? " AND active = 1" : ""), Long.class, BRANCH, entityType);
        if (id.isEmpty()) return List.of();
        return jdbc.query("SELECT step_no, name, role, min_amount FROM ecis_workflow_step WHERE workflow_id = ? ORDER BY step_no", (rs, i) -> new StepDef(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)), id.get(0));
    }

    /** Every kind of item a workflow can be set on, with its configuration (inactive and empty when none was saved). */
    public List<Definition> definitions() {
        List<Definition> out = new ArrayList<>();
        for (WorkflowHost h : hosts.values()) {
            List<Boolean> active = jdbc.queryForList("SELECT active FROM ecis_workflow WHERE branch_code = ? AND entity_type = ?", Boolean.class, BRANCH, h.entityType());
            out.add(new Definition(h.entityType(), h.label(), !active.isEmpty() && active.get(0), definitionSteps(h.entityType(), false).stream().map((s) -> new Step(s.no(), s.name(), s.role(), s.minAmount())).toList()));
        }
        return out;
    }

    /** Saves the configuration of one kind of item (steps are numbered in the order given). Items already under way keep the steps they started with. */
    public Definition save(String entityType, boolean active, List<Step> steps, String user) {
        host(entityType);
        List<StepDef> defs = new ArrayList<>();
        int n = 0;
        for (Step s : steps == null ? List.<Step>of() : steps) defs.add(new StepDef(++n, s.name() == null ? null : s.name().trim(), s.role(), s.minAmount()));
        List<String> problems = WorkflowRules.definitionProblems(defs);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        List<Long> found = jdbc.queryForList("SELECT id FROM ecis_workflow WHERE branch_code = ? AND entity_type = ? FOR UPDATE", Long.class, BRANCH, entityType);
        Definition before = definitions().stream().filter((d) -> d.entityType().equals(entityType)).findFirst().orElseThrow();
        long id;
        if (found.isEmpty()) {
            id = db.insertReturningId("INSERT INTO ecis_workflow (branch_code, entity_type, active, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)", "id", BRANCH, entityType, active ? 1 : 0, user, Timestamp.valueOf(now()));
        } else {
            id = found.get(0);
            jdbc.update("UPDATE ecis_workflow SET active = ?, updated_by = ?, updated_at = ? WHERE id = ?", active ? 1 : 0, user, Timestamp.valueOf(now()), id);
        }
        jdbc.update("DELETE FROM ecis_workflow_step WHERE workflow_id = ?", id);
        for (StepDef s : defs) jdbc.update("INSERT INTO ecis_workflow_step (workflow_id, step_no, name, role, min_amount) VALUES (?, ?, ?, ?, ?)", id, s.no(), s.name(), s.role(), s.minAmount());
        Definition after = definitions().stream().filter((d) -> d.entityType().equals(entityType)).findFirst().orElseThrow();
        AuditLog.write(jdbc, Timestamp.valueOf(now()), user, "workflow.configure", "ecis_workflow", entityType, before.toString(), after.toString(), null);
        return after;
    }
}
