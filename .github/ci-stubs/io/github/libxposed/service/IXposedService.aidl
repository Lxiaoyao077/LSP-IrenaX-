// Stand-in for the private libxposed/service submodule, used only by the throwaway CI branch.
//
// :daemon extends IXposedService.Stub, so it cannot be compiled without this interface, and the
// submodule it really comes from is private. This copy was taken from a public repository that
// vendors it; it is the API 100 naming form, which is what this repository's daemon overrides:
// getAPIVersion (not getApiVersion) and getFrameworkPrivilege (with getFrameworkProperties added
// on the daemon side as a plain method for the API 101 wire). Every @Override in
// LSPModuleService and every IXposedService constant the daemon references resolves against this
// file, so what it proves is that the daemon's own sources compile - not that it is byte for byte
// the pinned private revision.
package io.github.libxposed.service;
import io.github.libxposed.service.IXposedScopeCallback;

interface IXposedService {
    const int API = 100;

    const int FRAMEWORK_PRIVILEGE_ROOT = 0;
    const int FRAMEWORK_PRIVILEGE_CONTAINER = 1;
    const int FRAMEWORK_PRIVILEGE_APP = 2;
    const int FRAMEWORK_PRIVILEGE_EMBEDDED = 3;

    const String AUTHORITY_SUFFIX = ".XposedService";
    const String SEND_BINDER = "SendBinder";

    // framework details
    int getAPIVersion() = 1;
    String getFrameworkName() = 2;
    String getFrameworkVersion() = 3;
    long getFrameworkVersionCode() = 4;
    int getFrameworkPrivilege() = 5;

    // scope utilities
    List<String> getScope() = 10;
    oneway void requestScope(String packageName, IXposedScopeCallback callback) = 11;
    String removeScope(String packageName) = 12;

    // remote preference utilities
    Bundle requestRemotePreferences(String group) = 20;
    void updateRemotePreferences(String group, in Bundle diff) = 21;
    void deleteRemotePreferences(String group) = 22;

    // remote file utilities
    String[] listRemoteFiles() = 30;
    ParcelFileDescriptor openRemoteFile(String name) = 31;
    boolean deleteRemoteFile(String name) = 32;
}
