# 검색·고객센터 RAG용 Elasticsearch 이미지. 한국어 형태소 분석기 nori를 넣는다.
# 버전은 같은 폴더 elasticsearch.yaml의 spec.version과 같아야 한다.
# 방법: https://www.elastic.co/docs/deploy-manage/deploy/cloud-on-k8s/create-custom-images
# 로컬 빌드: docker build -t hapbang/elasticsearch-nori:9.5.5 k8s/platform/base/elasticsearch
FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.5
RUN bin/elasticsearch-plugin install --batch analysis-nori
