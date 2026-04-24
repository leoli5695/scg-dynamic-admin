import React, { useState, useEffect } from "react";
import {
  Card,
  Form,
  Input,
  Select,
  InputNumber,
  Button,
  Space,
  message,
  Spin,
  Tag,
  Tooltip,
  Alert,
  Row,
  Col,
  Divider,
} from "antd";
import {
  ClusterOutlined,
  ArrowLeftOutlined,
  CloudServerOutlined,
  SettingOutlined,
  CloudSyncOutlined,
  DatabaseOutlined,
  AppstoreOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";
import "../styles/form-page.css";

const { Option } = Select;
const { TextArea } = Input;

interface KubernetesCluster {
  id: number;
  clusterName: string;
  connectionStatus: string;
  clusterVersion: string;
}

interface InstanceSpec {
  type: string;
  cpuCores: number;
  memoryMB: number;
  description: string;
}

interface LocalImage {
  name: string;
  namespace?: string;
  image?: string;
  tag?: string;
}

const InstanceCreatePage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [specs, setSpecs] = useState<InstanceSpec[]>([]);

  // Watch form fields
  const selectedSpecType = Form.useWatch("specType", form);
  const selectedClusterId = Form.useWatch("clusterId", form);
  const selectedImagePullPolicy = Form.useWatch("imagePullPolicy", form);

  // Local image state
  const [localImages, setLocalImages] = useState<LocalImage[]>([]);
  const [loadingImages, setLoadingImages] = useState(false);
  const [selectedImageNamespace, setSelectedImageNamespace] = useState<string | undefined>(undefined);

  useEffect(() => {
    fetchData();
  }, []);

  // Load local images when cluster is selected and pull policy is "Never"
  useEffect(() => {
    if (selectedClusterId && selectedImagePullPolicy === "Never") {
      loadLocalImages(selectedClusterId);
    } else {
      setLocalImages([]);
      setSelectedImageNamespace(undefined);
    }
  }, [selectedClusterId, selectedImagePullPolicy]);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [clustersRes, specsRes] = await Promise.all([
        axios.get("/api/kubernetes/clusters"),
        axios.get("/api/instances/specs"),
      ]);
      setClusters(clustersRes.data.data || []);
      setSpecs(specsRes.data.data || []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
      message.error(t("common.fetch_error"));
    } finally {
      setLoading(false);
    }
  };

  const loadLocalImages = async (clusterId: number) => {
    setLoadingImages(true);
    try {
      const res = await axios.get(`/api/kubernetes/clusters/${clusterId}/images`);
      if (res.data.code === 200) {
        const rawImages = res.data.data || [];
        // Parse image names into structured format
        const parsed = rawImages.map((img: any) => {
          const fullName = img.name;
          // Parse image name: [registry/][namespace/]image[:tag]
          const parts = fullName.split('/');
          let namespace = '';
          let imageWithTag = fullName;
          
          if (parts.length >= 2 && !parts[0].includes(':')) {
            // Has namespace
            namespace = parts[0];
            imageWithTag = parts.slice(1).join('/');
          }
          
          const [image, tag] = imageWithTag.split(':');
          
          return {
            name: fullName,
            namespace: namespace || 'default',
            image: image,
            tag: tag || 'latest'
          };
        });
        
        setLocalImages(parsed);
        
        // Auto-select k8s.io namespace if available
        const k8sImages = parsed.filter((img: { namespace: string }) => img.namespace === 'k8s.io');
        if (k8sImages.length > 0) {
          setSelectedImageNamespace('k8s.io');
        }
      }
    } catch (error) {
      console.error("Failed to load local images:", error);
      message.warning("Failed to load local images from cluster");
      setLocalImages([]);
    } finally {
      setLoadingImages(false);
    }
  };

  // Filter images by namespace
  const filteredImages = selectedImageNamespace 
    ? localImages.filter((img: LocalImage) => img.namespace === selectedImageNamespace)
    : localImages;

  // Get unique namespaces
  const namespaces = Array.from(new Set(localImages.map((img: LocalImage) => img.namespace || 'default')));

  const handleSubmit = async (values: any) => {
    setSubmitting(true);
    try {
      const response = await axios.post("/api/instances", values);
      if (response.data.code === 200) {
        message.success(t("instance.create_success"));
        navigate("/instances");
      } else {
        message.error(response.data.message || t("instance.create_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("instance.create_failed"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    navigate("/instances");
  };

  return (
    <div className="form-page-container">
      <Card className="form-card">
        {/* Header */}
        <div className="form-header">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={handleCancel}
            className="form-header-back"
          />
          <div className="form-header-icon">
            <CloudServerOutlined />
          </div>
          <h3 className="form-header-title">{t("instance.create_instance")}</h3>
        </div>

        <Divider style={{ margin: '20px 0 0', borderColor: 'rgba(255,255,255,0.08)' }} />

        <Spin spinning={loading}>
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{
              specType: "medium",
              replicas: 1,
              createNamespace: true,
              image: "my-gateway:latest",
              imagePullPolicy: "Never",
              serverPort: 80,
              managementPort: 9091,
            }}
          >
            {/* Basic Info Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <AppstoreOutlined />
                </div>
                <span className="form-section-title">{t("instance.basic_info")}</span>
              </div>
              <div className="form-section-content">
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="instanceName"
                      label={t("instance.instance_name")}
                      rules={[{ required: true, message: t("common.required") }]}
                    >
                      <Input placeholder={t("instance.instance_name_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="clusterId"
                      label={t("instance.cluster")}
                      rules={[{ required: true, message: t("common.required") }]}
                    >
                      <Select placeholder={t("instance.select_cluster")} size="large">
                        {clusters
                          .filter((c) => c.connectionStatus === "CONNECTED")
                          .map((cluster) => (
                            <Option key={cluster.id} value={cluster.id}>
                              <Space>
                                <ClusterOutlined style={{ color: '#60a5fa' }} />
                                {cluster.clusterName}
                                <Tag color="blue" style={{ marginLeft: 8 }}>{cluster.clusterVersion}</Tag>
                              </Space>
                            </Option>
                          ))}
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="namespace"
                      label={t("instance.namespace")}
                      rules={[{ required: true, message: t("common.required") }]}
                    >
                      <Input placeholder={t("instance.namespace_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="createNamespace" label={t("instance.create_namespace")}>
                      <Select size="large">
                        <Option value={true}>{t("common.yes")}</Option>
                        <Option value={false}>{t("common.no")}</Option>
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>
              </div>
            </div>

            {/* Configuration Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <DatabaseOutlined />
                </div>
                <span className="form-section-title">{t("instance.config_section")}</span>
                <span className="form-section-subtitle">{t("instance.config_section_hint")}</span>
              </div>
              <div className="form-section-content">
                <Alert
                  message={t("instance.config_hint")}
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="nacosServerAddr"
                      label={t("instance.nacos_server_addr")}
                      tooltip={t("instance.nacos_server_addr_tooltip")}
                    >
                      <Input placeholder={t("instance.nacos_server_addr_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="redisServerAddr"
                      label={t("instance.redis_server_addr")}
                      tooltip={t("instance.redis_server_addr_tooltip")}
                    >
                      <Input placeholder={t("instance.redis_server_addr_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="jaegerServerAddr"
                      label={t("instance.jaeger_server_addr")}
                      tooltip={t("instance.jaeger_server_addr_tooltip")}
                    >
                      <Input placeholder={t("instance.jaeger_server_addr_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="prometheusServerAddr"
                      label={t("instance.prometheus_server_addr")}
                      tooltip={t("instance.prometheus_server_addr_tooltip")}
                    >
                      <Input placeholder={t("instance.prometheus_server_addr_placeholder")} size="large" />
                    </Form.Item>
                  </Col>
                </Row>
              </div>
            </div>

            {/* Resource Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <SettingOutlined />
                </div>
                <span className="form-section-title">{t("instance.resource_section")}</span>
              </div>
              <div className="form-section-content">
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item
                      name="specType"
                      label={t("instance.spec_type")}
                      rules={[{ required: true }]}
                    >
                      <Select size="large">
                        {specs.map((spec) => (
                          <Option key={spec.type} value={spec.type}>
                            {spec.type === "custom"
                              ? t("instance.spec_custom")
                              : t(`instance.spec_${spec.type}`)}
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  {selectedSpecType === "custom" && (
                    <>
                      <Col span={8}>
                        <Form.Item
                          name="cpuCores"
                          label={t("instance.cpu_cores")}
                          rules={[{ required: true, message: t("common.required") }]}
                        >
                          <InputNumber
                            min={0.1}
                            max={16}
                            step={0.5}
                            size="large"
                            style={{ width: "100%" }}
                            placeholder="1.0"
                          />
                        </Form.Item>
                      </Col>
                      <Col span={8}>
                        <Form.Item
                          name="memoryMB"
                          label={t("instance.memory_mb")}
                          rules={[{ required: true, message: t("common.required") }]}
                        >
                          <InputNumber
                            min={128}
                            max={16384}
                            step={128}
                            size="large"
                            style={{ width: "100%" }}
                            placeholder="1024"
                          />
                        </Form.Item>
                      </Col>
                    </>
                  )}
                  <Col span={8}>
                    <Form.Item
                      name="replicas"
                      label={t("instance.replicas")}
                      rules={[{ required: true }]}
                    >
                      <InputNumber min={1} max={10} size="large" style={{ width: "100%" }} />
                    </Form.Item>
                  </Col>
                </Row>
              </div>
            </div>

            {/* Image Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <CloudSyncOutlined />
                </div>
                <span className="form-section-title">{t("instance.image_section")}</span>
              </div>
              <div className="form-section-content">
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="imagePullPolicy"
                      label={t("instance.image_pull_policy")}
                      tooltip={t("instance.image_pull_policy_tooltip")}
                    >
                      <Select size="large" style={{ width: "100%" }}>
                        <Option value="IfNotPresent">{t("instance.pull_if_not_present")}</Option>
                        <Option value="Always">{t("instance.pull_always")}</Option>
                        <Option value="Never">{t("instance.pull_never")}</Option>
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="image"
                      label={t("instance.image")}
                      tooltip={
                        selectedImagePullPolicy === "Never" 
                          ? "Select from local images available on cluster nodes"
                          : t("instance.image_tooltip")
                      }
                    >
                      {selectedImagePullPolicy === "Never" ? (
                        loadingImages ? (
                          <Spin tip="Loading images..." />
                        ) : localImages.length > 0 ? (
                          <Space direction="vertical" style={{ width: "100%" }} size="small">
                            <Select
                              value={selectedImageNamespace || 'all'}
                              onChange={setSelectedImageNamespace}
                              size="large"
                              style={{ width: "100%" }}
                              placeholder="Filter by namespace"
                            >
                              <Option value="all">All Namespaces ({localImages.length})</Option>
                              {namespaces.map(ns => {
                                const count = localImages.filter((img) => img.namespace === ns).length;
                                return (
                                  <Option key={ns} value={ns}>
                                    {ns} ({count})
                                  </Option>
                                );
                              })}
                            </Select>
                            <Select
                              showSearch
                              size="large"
                              placeholder={selectedImageNamespace ? `Select from ${selectedImageNamespace}` : 'Select local image'}
                              optionFilterProp="children"
                              loading={loadingImages}
                              allowClear
                              style={{ width: "100%" }}
                              optionLabelProp="title"
                            >
                              {filteredImages.map((img, idx) => (
                                <Option key={idx} value={img.name} title={img.name}>
                                  <Space>
                                    <Tag color="blue">{img.namespace}</Tag>
                                    <span style={{ color: '#fafafa' }}>{img.image}</span>
                                    <Tag color="green">{img.tag}</Tag>
                                  </Space>
                                </Option>
                              ))}
                            </Select>
                          </Space>
                        ) : (
                          <Input 
                            size="large"
                            placeholder="No local images found. Enter manually." 
                            style={{ width: "100%" }} 
                          />
                        )
                      ) : (
                        <Input 
                          size="large"
                          placeholder="e.g., my-gateway:latest" 
                          style={{ width: "100%" }} 
                        />
                      )}
                    </Form.Item>
                  </Col>
                </Row>
                <Form.Item name="description" label={t("instance.description")}>
                  <TextArea rows={3} size="large" showCount maxLength={500} />
                </Form.Item>
              </div>
            </div>

            {/* Footer */}
            <div className="form-footer">
              <Button size="large" onClick={handleCancel}>{t("common.cancel")}</Button>
              <Button type="primary" size="large" htmlType="submit" loading={submitting}>
                {t("instance.create_and_deploy")}
              </Button>
            </div>
          </Form>
        </Spin>
      </Card>
    </div>
  );
};

export default InstanceCreatePage;