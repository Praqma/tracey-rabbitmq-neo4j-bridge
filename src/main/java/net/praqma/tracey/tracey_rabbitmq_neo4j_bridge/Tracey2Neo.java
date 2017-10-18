package net.praqma.tracey.tracey_rabbitmq_neo4j_bridge;


/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.config.Configuration;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/**
 *
 * @author sofus
 */
public class Tracey2Neo {
    

	private static SessionFactory sessionFactory;
	private Properties prop;

	public Tracey2Neo(Properties neoprop) {
		prop = neoprop;
		if (neoprop.size() == 0)
			System.err.println("no props");
		System.out.println(neoprop.getProperty("server_url"));
	}

	private Session getSession() {
		if (sessionFactory == null) {
			Configuration cf = new Configuration();
			cf.set("URI", prop.getProperty("URI"));
			cf.driverConfiguration().setDriverClassName("org.neo4j.ogm.drivers.http.driver.HttpDriver");
			sessionFactory = new SessionFactory(cf, "net.praqma.tracey");
		}
		return sessionFactory.openSession();
	}

	/**
	 * @param jsonString
	 */
	@Deprecated
	public void persist(String jsonString) {
		JsonObject json = new JsonParser().parse(jsonString).getAsJsonObject();
		Set<Entry<String, JsonElement>> paramSet = json.entrySet();
		int id = persistJsonObject(json, "Event");
		for (Entry<String, JsonElement> ent : paramSet) {
			JsonElement el = ent.getValue();
			if (el.isJsonObject()) {
				parseJson(el.getAsJsonObject(), id, ent.getKey());
			} else if (el.isJsonArray()) {
				JsonArray list = el.getAsJsonArray();
				for (JsonElement list1 : list) {
					parseJson(list1.getAsJsonObject(), id, ent.getKey());
				}
			}
		}
	}

	/**
	 * Could be used recursive
	 *
	 * @param json
	 * @param parentId
	 * @param relationType
	 */
	private void parseJson(JsonObject json, int parentId, String relationType) {
		if (json.entrySet().size() == 0)
			System.out.println(json.toString());
		int childId = persistJsonObject(json, relationType);
		makeRelation(parentId, childId, relationType);

		Set<Entry<String, JsonElement>> paramSet = json.entrySet();
		for (Entry<String, JsonElement> ent : paramSet) {
			JsonElement el = ent.getValue();
			if (el.isJsonObject()) {
				parseJson(el.getAsJsonObject(), childId, ent.getKey());
			} else if (el.isJsonArray()) {
				JsonArray list = el.getAsJsonArray();
				for (JsonElement list1 : list) {
					// TODO: What about array with primitives?!?
					if (list1.isJsonObject()) {
						parseJson(list1.getAsJsonObject(), childId, ent.getKey());
					}
				}
			}
		}

	}

	//This should return the internal id of the node we want to match in Neo4j. For Eiffel we need to look in
	//the relation [n:EventType]-[meta]->{id} and return
	//MATCH (n)-[meta]->(k:meta { id:"7829c39c-4739-4e1b-8ce8-f412714df3a7"}) return id(n)
	private Integer[] getNeoId(String id) {
		List<Integer> outs = new ArrayList<>();
		Session s = getSession();

		Map<String, Object> cypherParams = new HashMap<>();
		cypherParams.put("id", id);
		// MATCH(n:Event{id:"59a5a70bce877dfef8d3d9b3de74d87e9b26161f"}) return
		// MATCH (n)-[meta]->(k:meta { id:{id} }) return id(n)
		// MATCH (n)-[meta]->(k:meta { id:"7829c39c-4739-4e1b-8ce8-f412714df3a7" }) return n
		//String query = "MATCH (n { id:{id} }) return id(n)";
		String query = "MATCH (n)-[meta]->(k:meta { id:{id} }) return id(n)";
		s.beginTransaction();
		Result res = s.query(query, cypherParams, true);
		Iterable<Map<String, Object>> tmp = res.queryResults();

		for (Map<String, Object> row : tmp) {
			outs.add((Integer) row.get("id(n)"));
		}
		s.getTransaction().commit();
		if (outs.isEmpty())
			outs.add(createShadowNode(id));
		System.out.println(id + " gets translated to: ");
		System.out.println(outs);
		return outs.toArray(new Integer[outs.size()]);
	}

	private void makeRelation(int from, Integer[] to, String relationType) {
		for (int i = 0; i < to.length; i++) {
			makeRelation(from, to[i], relationType);
		}
	}

	/**
	 * Make relation between two nodes.
	 *
	 * @param from
	 * @param to
	 * @param relationType
	 */
	private void makeRelation(int from, int to, String relationType) {
		if (from == to)
			return;
		// System.out.println("Make relation" + from + to + relationType);
		Session s = getSession();
		Map<String, Object> cypherParams = new HashMap<>();
		cypherParams.put("from", from);
		cypherParams.put("to", to);
		String query = "MATCH (lft),(rgt)\n" + "WHERE id(rgt)={to} AND id(lft)={from}\n" + "CREATE UNIQUE (lft)-[r:"
				+ satisfyNoe4CrapNodeType(relationType) + "]->(rgt)\n" + "RETURN r";
		s.beginTransaction();
		s.query(query, cypherParams);
		s.getTransaction().commit();
	}

	private int persistJsonObject(JsonObject jo, String type) {
		int id = -1;
		Set<Entry<String, JsonElement>> paramSet = jo.entrySet();
		if (paramSet.isEmpty()) {
			return id;
		}
		Session s = getSession();
		StringBuilder sb = new StringBuilder(100);
		Map<String, Object> cypherParams = new HashMap<>();

		for (Entry<String, JsonElement> paramSet1 : paramSet) {
			if (paramSet1.getValue().isJsonPrimitive()) {
				String key = satisfyNeo4CrapParamValue(paramSet1.getKey());
				// Construct the key
				sb.append(key).append(":").append("{" + key + "},");
				cypherParams.put(key, getPrimitiveType(paramSet1.getValue()));
			}
		}
		if (sb.lastIndexOf(",") != -1)
			sb.deleteCharAt(sb.lastIndexOf(","));
		String query = "MERGE (n:" + satisfyNoe4CrapNodeType(type) + " {" + sb.toString() + "}) RETURN id(n)";
		System.out.println("\t" + query);
		s.beginTransaction();
		Result res = s.query(query, cypherParams);
		Iterable<Map<String, Object>> tmp = res.queryResults();
		for (Map<String, Object> row : tmp) {
			id = (Integer) row.get("id(n)");
		}
		s.getTransaction().commit();
		return id;
	}

	private JsonObject elementToObject(JsonElement el) {
		if (el.isJsonObject())
			return el.getAsJsonObject();

		JsonObject out = new JsonObject();
		out.add("data", el);
		return out;
	}

	// Ditched because NEO4J does not support nested maps. SIGHT!
	// public int createDataNode(JsonElement el, String type){
	// JsonObject obj= elementToObject(el);
	//
	// Session s = getSession();
	// int id = -1;
	// Map<String, Object> params = new HashMap<>();
	// params.put("props",JsonParserUtill.jsonToMap(obj));
	//
	// String query = "CREATE (n:Data) SET n={props}";
	// s.beginTransaction();
	// Result res = s.query(query, params);
	// Iterable<Map<String, Object>> tmp = res.queryResults();
	// for (Map<String, Object> row : tmp) {
	// id = (Integer) row.get("id(n)");
	// }
	// s.getTransaction().commit();
	//
	// return id;
	// }
	private int createShadowNode(String id) {
		// System.err.println("Creating Shadow node for: "+id);
		int out = -1;
		Session s = getSession();
		StringBuilder sb = new StringBuilder(100);
		Map<String, Object> cypherParams = new HashMap<>();

		sb.append("id : {id},");
		sb.append("type : {type}");

		cypherParams.put("id", id);
		cypherParams.put("type", "shadow_node");

		String query = "MERGE (n:Event {" + sb.toString() + "}) SET n:ShadowNode RETURN id(n)";
		s.beginTransaction();
		Result res = s.query(query, cypherParams);
		Iterable<Map<String, Object>> tmp = res.queryResults();
		for (Map<String, Object> row : tmp) {
			out = (Integer) row.get("id(n)");
		}
		s.getTransaction().commit();
		return out;
	}

	/**
	 * Method that persists the Entity object, along with it's data JSON,
	 * converted to nodes in NEO, with relations
	 *
	 * @param ent
	 * @return
	 */
	public int persistEvent(String ent) {
		int id = -1;
		Session s = getSession();
		StringBuilder sb = new StringBuilder(100);
		Map<String, Object> cypherParams = new HashMap<>();
		JsonParser parser = new JsonParser();
		JsonObject jsonEvent = parser.parse(ent).getAsJsonObject();
		DocumentContext dc;
		com.jayway.jsonpath.Configuration conf = com.jayway.jsonpath.Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);
		dc = JsonPath.using(conf).parse(ent);

		sb.append("n.type = {type},");
		sb.append("n.timestamp = {timestamp},");
		sb.append("n:"+dc.read("$.meta.type", String.class));

		cypherParams.put("id", dc.read("$.meta.id", String.class));
		cypherParams.put("type", dc.read("$.meta.type", String.class));
		cypherParams.put("timestamp", dc.read("$.meta.time", String.class));

		String query = "MERGE (n:ShadowNode { id:{id},type:\"shadow_node\"}) SET " + sb.toString()
				+ " REMOVE n:ShadowNode RETURN id(n)";
		s.beginTransaction();
		Result res = s.query(query, cypherParams);
		Iterable<Map<String, Object>> tmp = res.queryResults();
		for (Map<String, Object> row : tmp) {
			id = (Integer) row.get("id(n)");
		}
		s.getTransaction().commit();
//		String[] prev = dc.read("$.links[?(@.type =~ /.*CAUSE/i)].id");
		
		List<Map<String, String>> prev = dc.read("$.links[*]");
		// Make relation to the last entities
		for (Map<String, String> map : prev) {
			makeRelation(id, getNeoId(map.get("id")), map.get("type"));
		}

		persistDataField(jsonEvent.get("meta"), id, "meta");

		persistDataField(jsonEvent.get("data"), id, "Data");
		
		return id;
	}

	private void persistDataField(JsonElement elm, int id, String relationType) {
		if (elm.isJsonObject()) {
			parseJson(elm.getAsJsonObject(), id, relationType);
		}
		// call this method again for all the elements in the array
		else if (elm.isJsonArray()) {
			JsonArray ja = elm.getAsJsonArray();
			for (JsonElement jsonElement : ja) {
				persistDataField(jsonElement, id, relationType);
			}
		}
		// make an object out of the primitive to make a node in Neo4j
		else if (elm.isJsonPrimitive()) {
			JsonObject jo = new JsonObject();
			jo.add("data", elm);
			persistDataField(jo, id, relationType);
		}
	}

	private Object getPrimitiveType(JsonElement jo) {
		if (!jo.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive jp = jo.getAsJsonPrimitive();
		if (jp.isBoolean()) {
			return jp.getAsBoolean();
		}
		if (jp.isNumber()) {
			return jp.getAsNumber();
		}
		if (jp.isString()) {
			return jp.getAsString();
		}
		return null;
	}

	/**
	 * This is only due to the fact that a property key of a node cannot be a
	 * number. Sight!
	 *
	 * @param key
	 * @return
	 */
	private String satisfyNeo4CrapParamValue(String key) {
		char c = key.charAt(0);
		if (c >= '0' && c <= '9') {
			key = "n" + key;
		}
		return key;
	}

	private String satisfyNoe4CrapNodeType(String type) {
		return type.replace("/", "_");

	}

}
