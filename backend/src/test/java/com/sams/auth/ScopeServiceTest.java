package com.sams.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScopeServiceTest {

    @Test
    void adminFullSentinel() {
        CurrentUser admin = new CurrentUser(1, "a", 1, true, java.util.Map.of(), List.of(), false, "j");
        assertTrue(ScopeService.visibleScopes(admin).contains("*"));
    }

    @Test
    void siteInheritance() {
        List<String> scopes = List.of("site:Globe");
        assertTrue(ScopeService.siteRowVisible(scopes, "Globe", "存量"));
        assertFalse(ScopeService.siteRowVisible(scopes, "Smart", "存量"));
        assertTrue(ScopeService.importTargetVisible(scopes, "area", "Globe", null));
        assertFalse(ScopeService.importTargetVisible(scopes, "area", "Dito", null));
    }

    @Test
    void areaNodeDoesNotGrantCategory() {
        List<String> scopes = List.of("site:Globe:AREA");
        assertFalse(ScopeService.siteRowVisible(scopes, "Globe", "存量"));
        assertTrue(ScopeService.areaScopeOperators(scopes).contains("Globe"));
    }

    @Test
    void validateNodes() {
        assertTrue(ScopeService.validateScopeNode("site"));
        assertTrue(ScopeService.validateScopeNode("site:Globe:EXISTING"));
        assertTrue(ScopeService.validateScopeNode("site:Dito:AREA"));
        assertFalse(ScopeService.validateScopeNode("site:Foo"));
        assertNull(ScopeService.siteScopePairs(List.of("*")));
    }
}
