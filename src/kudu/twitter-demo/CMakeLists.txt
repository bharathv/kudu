# Copyright (c) 2013, Cloudera, inc.

find_library(LIBOAUTH_LIBRARY NAMES oauth)
if(NOT LIBOAUTH_LIBRARY)
  message(WARNING "libcurl or liboauth not found on system. Skipping twitter demo")
else()

  add_library(twitter_demo
    oauth.cc
    parser.cc
    insert_consumer.cc
    twitter_streamer.cc
    )
  target_link_libraries(twitter_demo
    client
    ${LIBOAUTH_LIBRARY}
    ${CURL_LIBRARIES}
    ${KUDU_BASE_LIBS})

  # Require that the tserver protobuf code is generated first
  add_dependencies(twitter_demo
    tserver_proto)

  add_executable(ingest_firehose ingest_firehose.cc)
  target_link_libraries(ingest_firehose
    twitter_demo)

  # Tests
  ADD_KUDU_TEST(oauth-test)
  target_link_libraries(oauth-test
    twitter_demo)

  ADD_KUDU_TEST(parser-test)
  target_link_libraries(parser-test
    twitter_demo)

endif() # library checks