package local.governance.guard;

public record GuardRule(
        String id,
        boolean enabled,
        GuardEffect effect,
        int priority,
        String marker) {}
