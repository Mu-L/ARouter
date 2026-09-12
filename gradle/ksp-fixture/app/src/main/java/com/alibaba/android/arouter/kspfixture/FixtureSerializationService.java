package com.alibaba.android.arouter.kspfixture;

import android.content.Context;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.service.SerializationService;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

@Route(path = "/ksp/serialization")
public final class FixtureSerializationService implements SerializationService {
    @Override public void init(Context context) {}
    @Override public <T> T json2Object(String input, Class<T> clazz) {
        throw new UnsupportedOperationException("Generic parseObject is required");
    }
    @Override public String object2Json(Object instance) {
        JSONArray array = new JSONArray();
        for (Object item : (List<?>) instance) { array.put(((Payload) item).value); }
        return array.toString();
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T parseObject(String input, Type type) {
        if (!(type instanceof ParameterizedType)) {
            throw new IllegalStateException("Generic injection lost ParameterizedType: " + type);
        }
        ParameterizedType parameterized = (ParameterizedType) type;
        Type element = parameterized.getActualTypeArguments()[0];
        if (element instanceof WildcardType) { element = ((WildcardType) element).getUpperBounds()[0]; }
        if (parameterized.getRawType() != List.class || element != Payload.class) {
            throw new IllegalStateException("Wrong generic payload type: " + type);
        }
        if (input == null || "null".equals(input)) { return null; }
        try {
            JSONArray json = new JSONArray(input);
            List<Payload> result = new ArrayList<>();
            for (int i = 0; i < json.length(); i++) { result.add(new Payload(json.getString(i))); }
            return (T) result;
        } catch (JSONException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
