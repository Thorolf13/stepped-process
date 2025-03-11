package io.github.thorolf13.steppedprocess.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ListUtils {
    public static  <T> List<T> concat(List<T> ...lists){
        List<T> result = new ArrayList<>();
        for (List<T> list : lists) {
            if( !isEmpty(list)){
                result.addAll(list);
            }
        }
        return result;
    }

    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }
}
