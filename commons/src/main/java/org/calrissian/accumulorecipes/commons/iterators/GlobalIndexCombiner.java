/*
 * Copyright (C) 2014 The Calrissian Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.calrissian.accumulorecipes.commons.iterators;


import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.Combiner;
import org.calrissian.accumulorecipes.commons.support.qfd.GlobalIndexValue;

import java.util.Iterator;

public class GlobalIndexCombiner extends Combiner {

    @Override
    public Value reduce(Key key, Iterator<Value> valueIterator) {

        long cardinality = 0;
        long expiration = 0;

        while(valueIterator.hasNext()) {

            GlobalIndexValue value = new GlobalIndexValue(valueIterator.next());

            cardinality += value.getCardinatlity();

            if(value.getExpiration() == -1)
                expiration = -1;

            if(expiration != -1)
                expiration = Math.max(expiration, value.getExpiration());
        }

        return new GlobalIndexValue(cardinality, expiration).toValue();
    }
}