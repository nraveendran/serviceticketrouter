# AI-Powered Civic Service Routing & Investigation Platform

## Overview

This project explores how AI can improve municipal and operational support workflows by automatically routing vague citizen-reported issues to the correct department and service workflow.

Using publicly available Dallas 311 service request data (~2.7M records), the platform combines semantic search, vector embeddings, retrieval pipelines, and evaluation frameworks to simulate a modern AI-assisted civic operations system.

Example citizen complaints:

- “Street light blinking all night”
- “Water pooling near intersection”
- “Big pothole near school”
- “Car blocking my driveway”

The platform predicts:

- Service Request Type
- Responsible Department
- Operational Priority

---
## How AI Is Used

The platform does not rely on simple keyword matching.

Instead, it uses semantic embeddings to understand the meaning of citizen complaints and retrieve operationally similar historical examples.

To improve routing quality, the system generates structured metadata for each service category and creates realistic synthetic citizen complaints with:

- vague wording
- ambiguous phrasing
- typo-heavy language
- category-specific terminology
- hard negative examples

This allows the routing engine to learn distinctions such as:

- streetlight vs traffic signal
- parking violation vs sign maintenance
- drainage issue vs pothole

The project also includes an offline evaluation pipeline measuring:

- routing accuracy
- top-N retrieval quality
- confidence scoring
- category confusion patterns
- taxonomy normalization issues

---

## Business Value / ROI

This type of platform can reduce operational overhead by:

- reducing manual ticket triage
- improving routing consistency
- accelerating dispatch decisions
- reducing duplicate service requests
- identifying recurring infrastructure issues earlier
- improving citizen response times

The architecture is intentionally designed to support high-volume operational workflows where human dispatchers or support teams currently spend significant time interpreting and routing vague requests.


## High-Level Architecture

```text
Citizen Complaint
        ↓
Embedding Generation
        ↓
Vector Similarity Search (pgvector)
        ↓
Top Candidate Retrieval
        ↓
AI Ranking & Route Prediction
        ↓
Department / Priority Recommendation
```

The current implementation uses:

- Java + Spring Boot
- PostgreSQL + pgvector
- OpenAI embeddings
- Retrieval-based semantic routing
- Synthetic training data generation
- Evaluation pipelines and confusion analysis

---

