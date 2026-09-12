package com.alibaba.android.arouter.kspfixture;

import android.os.Bundle;
import com.alibaba.android.arouter.kspfixture.legacy.LegacyInjectedFragment;
import com.alibaba.android.arouter.kspfixture.legacy.LegacyService;
import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Route(path = "/ksp/java-fragment")
public class JavaFragment extends LegacyInjectedFragment {
    @Autowired public int count = 7;
    @Autowired public Integer boxed = 9;
    @Autowired public String text = "java-default";
    @Autowired public List<Payload> payloads = Collections.singletonList(new Payload("java-default"));
    @Autowired public ArrayList<String> serializable = new ArrayList<>(Collections.singletonList("default"));
    @Autowired public Bundle parcelable;
    @Autowired public KspService typedProvider;
    @Autowired(name = "/legacy/provider") public LegacyService namedProvider;

    public JavaFragment() {}
}
