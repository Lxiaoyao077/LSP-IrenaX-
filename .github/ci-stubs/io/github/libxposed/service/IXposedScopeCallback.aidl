// Stand-in for the private libxposed/service submodule, used only by the throwaway CI branch.
package io.github.libxposed.service;

interface IXposedScopeCallback {
    oneway void onScopeRequestApproved(in List<String> approved) = 1;
    oneway void onScopeRequestFailed(String message) = 2;
}
