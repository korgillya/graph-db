##Based on
[Akka sample cqrs](https://github.com/akka/akka-samples/tree/2.6/akka-sample-cqrs-scala)

[Building a Distributed Graph Database with Akka 2.6](https://medium.com/@zhao258147/building-a-distributed-graph-database-with-akka-2-6-a107f6f83eff)

## Running the sample code

1. Start a Cassandra server by running:

```
sbt "runMain sample.cqrs.Main cassandra"
```

2. Start a node that runs the write model:

```
sbt -Dakka.cluster.roles.0=write-model "runMain sample.cqrs.Main 2551"
```

3. Start a node that runs the read model:

```
sbt -Dakka.cluster.roles.0=read-model "runMain sample.cqrs.Main 2552"
```

4. More write or read nodes can be started started by defining roles and port:

```
sbt -Dakka.cluster.roles.0=write-model "runMain sample.cqrs.Main 2553"
sbt -Dakka.cluster.roles.0=read-model "runMain sample.cqrs.Main 2554"
```

Try it with curl:

```
# add people POST http://127.0.0.1:8051/graphdb/node
Request:
{
  "nodeId": "jerry",
  "nodeType": "Person",
  "properties": [
    {
      "name": "name",
      "type": "String",
      "value": "Jerry"
    },
    {
      "name": "age",
      "type": "int",
      "value": "50"
    }
  ]
}
Response:
{
    "nodeId": "",
    "nodeType": "Person",
    "properties": {
        "name": "Jerry",
        "age": "50"
    },
    "relations": []
}

# add edge POST http://127.0.0.1:8051/graphdb/edge
Request:
{
  "sourceId":"tom",
  "targetId":"jerry",
  "name":"FRIEND",
  "properties":[]
}

Response: OK

# find all friends of a given person POST http://127.0.0.1:8051/graphdb/match
Request:
{
	"startNode": {
		"nodeType": "Person",
		"properties": {"name":"Mike"},
		"needReturn": false
	},
	"relation": {
		"name":"FRIEND"
	},
	"targetNode": {
		"nodeType": "Person",
		"properties": {},
		"needReturn": true
	}
}

Response:
{
    "matches": [
        {
            "nodeId": "tom",
            "nodeType": "Person",
            "properties": {
                "age": "45",
                "name": "Tom"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "coffeeshop"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "cto"
                ],
                [
                    "FRIEND",
                    "Target",
                    "donald"
                ],
                [
                    "FRIEND",
                    "Target",
                    "jerry"
                ],
                [
                    "FRIEND",
                    "Target",
                    "mike"
                ]
            ]
        }
    ]
}



# List all​ ​employed persons POST http://127.0.0.1:8051/graphdb/match
Request:
{
	"startNode": {
		"nodeType": "Person",
		"properties": {},
		"needReturn": true
	},
	"relation": {
		"name":"EMPLOYEED_BY"
	},
	"targetNode": {
		"nodeType": "Business",
		"properties": {},
		"needReturn": false
	}
}

Response:
{
    "matches": [
        {
            "nodeId": "donald",
            "nodeType": "Person",
            "properties": {
                "age": "33",
                "name": "Donald"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "grocery"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "manager"
                ],
                [
                    "FRIEND",
                    "Source",
                    "tom"
                ],
                [
                    "FRIEND",
                    "Target",
                    "jerry"
                ]
            ]
        },
        {
            "nodeId": "jerry",
            "nodeType": "Person",
            "properties": {
                "age": "50",
                "name": "Jerry"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "bakery"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "ceo"
                ],
                [
                    "FRIEND",
                    "Source",
                    "donald"
                ],
                [
                    "FRIEND",
                    "Source",
                    "tom"
                ]
            ]
        },
        {
            "nodeId": "tom",
            "nodeType": "Person",
            "properties": {
                "age": "45",
                "name": "Tom"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "coffeeshop"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "cto"
                ],
                [
                    "FRIEND",
                    "Target",
                    "donald"
                ],
                [
                    "FRIEND",
                    "Target",
                    "jerry"
                ],
                [
                    "FRIEND",
                    "Target",
                    "mike"
                ]
            ]
        }
    ]
}


#List all persons employed by a business located in the area X POST http://127.0.0.1:8051/graphdb/match
Request:
{
	"startNode": {
		"nodeType": "Person",
		"properties": {},
		"needReturn": true
	},
	"relation": {
		"name":"EMPLOYEED_BY"
	},
	"targetNode": {
		"nodeType": "Business",
		"properties": {"location": "New York"},
		"needReturn": false
	}
}

Response:
{
    "matches": [
        {
            "nodeId": "tom",
            "nodeType": "Person",
            "properties": {
                "age": "45",
                "name": "Tom"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "coffeeshop"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "cto"
                ],
                [
                    "FRIEND",
                    "Target",
                    "donald"
                ],
                [
                    "FRIEND",
                    "Target",
                    "jerry"
                ],
                [
                    "FRIEND",
                    "Target",
                    "mike"
                ]
            ]
        },
        {
            "nodeId": "jerry",
            "nodeType": "Person",
            "properties": {
                "age": "50",
                "name": "Jerry"
            },
            "relations": [
                [
                    "EMPLOYEED_BY",
                    "Source",
                    "bakery"
                ],
                [
                    "FOLLOWING",
                    "Source",
                    "ceo"
                ],
                [
                    "FRIEND",
                    "Source",
                    "donald"
                ],
                [
                    "FRIEND",
                    "Source",
                    "tom"
                ]
            ]
        }
    ]
}

```

