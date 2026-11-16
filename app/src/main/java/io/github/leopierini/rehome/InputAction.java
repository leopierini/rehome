package io.github.leopierini.rehome;

/** The Inputs menu's `input_action` (config.xml or a theme): an intent action, or an activity to start. */
final class InputAction {
    private InputAction() {
    }

    /** True when `input_action` names an activity ("package/.Activity", "package/package.Activity") rather than an
     *  intent action. Actions never contain a slash, so the two forms cannot be confused. */
    static boolean isComponent(String inputAction) {
        if (inputAction == null) return false;
        int slash = inputAction.indexOf('/');
        return slash > 0 && slash < inputAction.length() - 1 && inputAction.indexOf('/', slash + 1) < 0;
    }
}
