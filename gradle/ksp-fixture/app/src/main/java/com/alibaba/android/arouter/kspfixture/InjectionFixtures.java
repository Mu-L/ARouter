package com.alibaba.android.arouter.kspfixture;

import androidx.fragment.app.Fragment;
import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;

public final class InjectionFixtures {
    private InjectionFixtures() {}

    @Route(path = "/ksp/nested")
    public static final class NestedFragment extends Fragment {
        @Autowired public String nested = "nested-default";
    }

    public static final class MissingRequiredProvider {
        @Autowired(name = "/missing/provider", required = true) public KspService missing;
    }
}
