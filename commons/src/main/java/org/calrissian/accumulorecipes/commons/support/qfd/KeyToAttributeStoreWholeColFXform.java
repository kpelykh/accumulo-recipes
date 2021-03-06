/*
 * Copyright (C) 2013 The Calrissian Authors
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
package org.calrissian.accumulorecipes.commons.support.qfd;

import static org.apache.commons.lang.StringUtils.splitPreserveAllTokens;
import static org.calrissian.accumulorecipes.commons.support.Constants.NULL_BYTE;
import static org.calrissian.accumulorecipes.commons.support.Constants.ONE_BYTE;
import static org.calrissian.accumulorecipes.commons.util.RowEncoderUtil.decodeRow;
import static org.calrissian.accumulorecipes.commons.support.attribute.Metadata.Visiblity.setVisibility;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.kryo.Kryo;
import com.google.common.base.Function;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.calrissian.accumulorecipes.commons.support.attribute.metadata.MetadataSerDe;
import org.calrissian.mango.domain.Attribute;
import org.calrissian.mango.domain.AttributeStore;
import org.calrissian.mango.domain.BaseAttributeStoreBuilder;
import org.calrissian.mango.types.TypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class KeyToAttributeStoreWholeColFXform<V extends AttributeStore, B extends BaseAttributeStoreBuilder<V,B>> implements Function<Map.Entry<Key,Value>,V> {

  public static final Logger log = LoggerFactory.getLogger(KeyToAttributeStoreWholeColFXform.class);

  private Kryo kryo;
  private TypeRegistry<String> typeRegistry;
  private MetadataSerDe metadataSerDe;

  public KeyToAttributeStoreWholeColFXform(Kryo kryo, TypeRegistry<String> typeRegistry, MetadataSerDe metadataSerDe) {
    this.kryo = kryo;
    this.typeRegistry = typeRegistry;
    this.metadataSerDe = metadataSerDe;
  }

  protected Kryo getKryo() {
    return kryo;
  }

  @Override
  public V apply(Map.Entry<Key,Value> keyValueEntry) {
    try {

      B entry = buildEntryFromKey(keyValueEntry.getKey());

      List<Map.Entry<Key,Value>> groupedKVs = decodeRow(keyValueEntry.getKey(), keyValueEntry.getValue());

      for (Map.Entry<Key,Value> groupedEvent : groupedKVs) {
          ByteArrayInputStream bais = new ByteArrayInputStream(groupedEvent.getValue().get());
          DataInputStream dis = new DataInputStream(bais);
          dis.readInt();    // number of keys/values
          dis.readLong();   // minimum expiration of keys and values

        List<Map.Entry<Key,Value>> keyValues = decodeRow(groupedEvent.getKey(), bais);

        for (Map.Entry<Key,Value> curEntry : keyValues) {

          String[] colQParts = splitPreserveAllTokens(curEntry.getKey().getColumnQualifier().toString(), NULL_BYTE);
          String[] aliasValue = splitPreserveAllTokens(colQParts[1], ONE_BYTE);
          String visibility = curEntry.getKey().getColumnVisibility().toString();

          try {
            Map<String,String> meta = metadataSerDe.deserialize(curEntry.getValue().get());
            Map<String,String> metadata = (meta == null ? new HashMap<String,String>() : new HashMap<String,String>(meta));
            setVisibility(metadata, visibility);
            Attribute attribute = new Attribute(colQParts[0], typeRegistry.decode(aliasValue[0], aliasValue[1]), metadata);
            entry.attr(attribute);
          } catch (Exception e) {
            log.error("There was an error deserializing the metadata for a attribute", e);
          }
        }
      }

      return entry.build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected abstract B buildEntryFromKey(Key k);

};
